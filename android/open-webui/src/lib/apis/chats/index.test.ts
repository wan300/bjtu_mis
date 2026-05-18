import { beforeEach, describe, expect, it, vi } from 'vitest';

const idbMock = vi.hoisted(() => {
	const stores = new Map<string, Map<string, any>>();

	const clone = (value: any) => (value === undefined ? undefined : JSON.parse(JSON.stringify(value)));
	const ensureStore = (name: string) => {
		if (!stores.has(name)) {
			stores.set(name, new Map());
		}
		return stores.get(name)!;
	};

	const keyFor = (value: any) => value?.id ?? value?.key;

	const createObjectStore = (name: string) => {
		ensureStore(name);
		return {
			createIndex: vi.fn()
		};
	};

	const db = {
		objectStoreNames: {
			contains: (name: string) => stores.has(name)
		},
		createObjectStore,
		put: vi.fn(async (storeName: string, value: any) => {
			ensureStore(storeName).set(keyFor(value), clone(value));
			return keyFor(value);
		}),
		get: vi.fn(async (storeName: string, key: string) => clone(ensureStore(storeName).get(key))),
		getAll: vi.fn(async (storeName: string) =>
			Array.from(ensureStore(storeName).values()).map(clone)
		),
		getAllFromIndex: vi.fn(async (storeName: string, indexName: string, key: string) =>
			Array.from(ensureStore(storeName).values())
				.filter((value) => value?.[indexName] === key)
				.map(clone)
		),
		delete: vi.fn(async (storeName: string, key: string) => {
			ensureStore(storeName).delete(key);
		}),
		clear: vi.fn(async (storeName: string) => {
			ensureStore(storeName).clear();
		}),
		transaction: vi.fn((storeName: string) => {
			const store = ensureStore(storeName);
			return {
				store: {
					put: vi.fn(async (value: any) => {
						store.set(keyFor(value), clone(value));
						return keyFor(value);
					}),
					delete: vi.fn(async (key: string) => {
						store.delete(key);
					}),
					clear: vi.fn(async () => {
						store.clear();
					})
				},
				done: Promise.resolve()
			};
		})
	};

	return {
		openDB: vi.fn(async (_name: string, _version: number, options?: any) => {
			options?.upgrade?.(db);
			return db;
		}),
		reset: () => stores.clear()
	};
});

vi.mock('idb', () => ({
	openDB: idbMock.openDB
}));

vi.mock('$lib/local-first', async (importOriginal) => {
	const actual = await importOriginal<typeof import('$lib/local-first')>();
	return {
		...actual,
		isLocalFirstClient: () => true
	};
});

import { getTaskIdsByChatId } from '$lib/apis';
import { createNewChat, getChatList } from './index';

describe('local-first chat APIs', () => {
	beforeEach(() => {
		idbMock.reset();
		vi.clearAllMocks();
	});

	it('persists a new chat and lists it with a title derived from the first user message', async () => {
		const history = {
			currentId: 'assistant-1',
			messages: {
				'user-1': {
					id: 'user-1',
					parentId: null,
					childrenIds: ['assistant-1'],
					role: 'user',
					content: '帮我总结最近的邮件',
					timestamp: 1
				},
				'assistant-1': {
					id: 'assistant-1',
					parentId: 'user-1',
					childrenIds: [],
					role: 'assistant',
					content: '',
					done: false,
					timestamp: 2
				}
			}
		};

		const saved = await createNewChat(
			'local-first-token',
			{
				models: ['gpt-test'],
				history,
				messages: Object.values(history.messages),
				timestamp: Date.now()
			},
			null
		);

		const chatList = await getChatList('local-first-token', 1);

		expect(saved.title).toBe('帮我总结最近的邮件');
		expect(chatList).toHaveLength(1);
		expect(chatList[0]).toMatchObject({
			id: saved.id,
			title: '帮我总结最近的邮件'
		});
		expect(chatList[0].time_range).toBeTruthy();
	});

	it('returns the expected task id payload shape in local-first mode', async () => {
		await expect(getTaskIdsByChatId('local-first-token', 'chat-1')).resolves.toEqual({
			task_ids: []
		});
	});
});
