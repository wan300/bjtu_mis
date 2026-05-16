import { openDB, type DBSchema, type IDBPDatabase } from 'idb';
import { getDefaultLocalSettings, mergeLocalSettings } from './index';
import { prepareDirectConnectionsForSecureStore } from './secrets';

const DB_NAME = 'open-webui-local-first';
const DB_VERSION = 2;

export type LocalChatRecord = {
	id: string;
	user_id: string;
	title: string;
	chat: Record<string, any>;
	meta?: Record<string, any>;
	folder_id?: string | null;
	pinned?: boolean;
	archived?: boolean;
	created_at: number;
	updated_at: number;
};

export type LocalFileRecord = {
	id: string;
	user_id: string;
	filename: string;
	name: string;
	type: string;
	size: number;
	content_type: string;
	status: string;
	created_at: number;
	updated_at: number;
	data?: Record<string, any>;
	url?: string;
	metadata?: Record<string, any> | null;
};

export type LocalKnowledgeChunkRecord = {
	id: string;
	source_type: 'file' | 'chat' | 'note' | 'memory';
	source_id: string;
	title: string;
	content: string;
	metadata?: Record<string, any> | null;
	created_at: number;
	updated_at: number;
};

export type LocalMemoryRecord = {
	id: string;
	content: string;
	tags?: string[];
	metadata?: Record<string, any> | null;
	created_at: number;
	updated_at: number;
};

export type LocalNoteRecord = {
	id: string;
	title: string;
	content: string;
	tags?: string[];
	metadata?: Record<string, any> | null;
	created_at: number;
	updated_at: number;
};

export type LocalTaskRecord = {
	id: string;
	title: string;
	description?: string;
	status: 'todo' | 'in_progress' | 'done' | 'cancelled';
	due_at?: number | null;
	metadata?: Record<string, any> | null;
	created_at: number;
	updated_at: number;
};

interface LocalFirstDB extends DBSchema {
	meta: {
		key: string;
		value: { key: string; value: any };
	};
	chats: {
		key: string;
		value: LocalChatRecord;
		indexes: {
			updated_at: number;
			created_at: number;
		};
	};
	files: {
		key: string;
		value: LocalFileRecord;
		indexes: {
			updated_at: number;
			filename: string;
		};
	};
	knowledge_chunks: {
		key: string;
		value: LocalKnowledgeChunkRecord;
		indexes: {
			source_type: string;
			source_id: string;
			updated_at: number;
		};
	};
	memories: {
		key: string;
		value: LocalMemoryRecord;
		indexes: {
			updated_at: number;
		};
	};
	notes: {
		key: string;
		value: LocalNoteRecord;
		indexes: {
			updated_at: number;
		};
	};
	tasks: {
		key: string;
		value: LocalTaskRecord;
		indexes: {
			status: string;
			due_at: number;
			updated_at: number;
		};
	};
}

let dbPromise: Promise<IDBPDatabase<LocalFirstDB>> | null = null;

const getDb = () => {
	if (!dbPromise) {
		dbPromise = openDB<LocalFirstDB>(DB_NAME, DB_VERSION, {
			upgrade(db) {
				if (!db.objectStoreNames.contains('meta')) {
					db.createObjectStore('meta', { keyPath: 'key' });
				}

				if (!db.objectStoreNames.contains('chats')) {
					const store = db.createObjectStore('chats', { keyPath: 'id' });
					store.createIndex('updated_at', 'updated_at');
					store.createIndex('created_at', 'created_at');
				}

				if (!db.objectStoreNames.contains('files')) {
					const store = db.createObjectStore('files', { keyPath: 'id' });
					store.createIndex('updated_at', 'updated_at');
					store.createIndex('filename', 'filename');
				}

				if (!db.objectStoreNames.contains('knowledge_chunks')) {
					const store = db.createObjectStore('knowledge_chunks', { keyPath: 'id' });
					store.createIndex('source_type', 'source_type');
					store.createIndex('source_id', 'source_id');
					store.createIndex('updated_at', 'updated_at');
				}

				if (!db.objectStoreNames.contains('memories')) {
					const store = db.createObjectStore('memories', { keyPath: 'id' });
					store.createIndex('updated_at', 'updated_at');
				}

				if (!db.objectStoreNames.contains('notes')) {
					const store = db.createObjectStore('notes', { keyPath: 'id' });
					store.createIndex('updated_at', 'updated_at');
				}

				if (!db.objectStoreNames.contains('tasks')) {
					const store = db.createObjectStore('tasks', { keyPath: 'id' });
					store.createIndex('status', 'status');
					store.createIndex('due_at', 'due_at');
					store.createIndex('updated_at', 'updated_at');
				}
			}
		});
	}

	return dbPromise;
};

const now = () => Date.now();

const uuid = () => {
	if (typeof crypto !== 'undefined' && crypto.randomUUID) {
		return crypto.randomUUID();
	}

	return `local-${Date.now()}-${Math.random().toString(16).slice(2)}`;
};

const getTextFromContent = (content: unknown) => {
	if (typeof content === 'string') {
		return content;
	}

	if (Array.isArray(content)) {
		return content
			.map((part) => {
				if (typeof part === 'string') return part;
				if (part?.type === 'text') return part.text ?? '';
				return '';
			})
			.join(' ')
			.trim();
	}

	return '';
};

const deriveTitle = (chat: Record<string, any>, fallback = 'New Chat') => {
	const explicitTitle = chat?.title?.trim?.();
	if (explicitTitle) {
		return explicitTitle;
	}

	const messages = Object.values(chat?.history?.messages ?? {});
	const firstUserMessage = messages.find((message: any) => message?.role === 'user') as any;
	const text = getTextFromContent(firstUserMessage?.content).replace(/\s+/g, ' ').trim();
	return text ? text.slice(0, 80) : fallback;
};

const normalizeRecord = (record: LocalChatRecord): LocalChatRecord => ({
	...record,
	chat: {
		...(record.chat as Record<string, any>),
		id: record.id,
		title: record.title
	},
	pinned: record.pinned ?? false,
	archived: record.archived ?? false,
	folder_id: record.folder_id ?? null
});

const getFileContent = (file: LocalFileRecord) => {
	const data = file?.data ?? {};
	if (typeof data.content === 'string') return data.content;
	if (typeof data.text === 'string') return data.text;
	if (typeof data.markdown === 'string') return data.markdown;
	return '';
};

const getChatIndexText = (chat: LocalChatRecord) =>
	Object.values(chat.chat?.history?.messages ?? {})
		.map((message: any) => {
			const role = message?.role ? `${message.role}: ` : '';
			return `${role}${getTextFromContent(message?.content)}`.trim();
		})
		.filter(Boolean)
		.join('\n');

const tokenize = (value: string) =>
	Array.from(
		new Set(
			value
				.toLowerCase()
				.split(/[^\p{L}\p{N}_]+/u)
				.map((token) => token.trim())
				.filter((token) => token.length >= 2)
		)
	);

const scoreTextMatch = (query: string, value: string) => {
	const normalizedQuery = query.toLowerCase().trim();
	const normalizedValue = value.toLowerCase();
	if (!normalizedQuery || !normalizedValue) return 0;

	let score = normalizedValue.includes(normalizedQuery) ? 20 : 0;
	for (const token of tokenize(normalizedQuery)) {
		let index = normalizedValue.indexOf(token);
		while (index !== -1) {
			score += 1;
			index = normalizedValue.indexOf(token, index + token.length);
		}
	}

	return score;
};

const buildKnowledgeChunks = ({
	sourceType,
	sourceId,
	title,
	content,
	metadata,
	timestamp = now()
}: {
	sourceType: LocalKnowledgeChunkRecord['source_type'];
	sourceId: string;
	title: string;
	content: string;
	metadata?: Record<string, any> | null;
	timestamp?: number;
}) => {
	const cleanContent = content.replace(/\s+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
	if (!cleanContent) return [];

	const chunkSize = 1800;
	const overlap = 180;
	const chunks: LocalKnowledgeChunkRecord[] = [];
	let cursor = 0;
	let index = 0;

	while (cursor < cleanContent.length) {
		const end = Math.min(cursor + chunkSize, cleanContent.length);
		const chunk = cleanContent.slice(cursor, end).trim();
		if (chunk) {
			chunks.push({
				id: `${sourceType}:${sourceId}:${index}`,
				source_type: sourceType,
				source_id: sourceId,
				title,
				content: chunk,
				metadata: metadata ?? null,
				created_at: timestamp,
				updated_at: timestamp
			});
		}

		if (end >= cleanContent.length) break;
		cursor = Math.max(end - overlap, cursor + 1);
		index += 1;
	}

	return chunks;
};

const replaceKnowledgeChunksForSource = async (
	sourceType: LocalKnowledgeChunkRecord['source_type'],
	sourceId: string,
	chunks: LocalKnowledgeChunkRecord[]
) => {
	const db = await getDb();
	const existing = await db.getAllFromIndex('knowledge_chunks', 'source_id', sourceId);
	const tx = db.transaction('knowledge_chunks', 'readwrite');

	for (const chunk of existing.filter((chunk) => chunk.source_type === sourceType)) {
		await tx.store.delete(chunk.id);
	}

	for (const chunk of chunks) {
		await tx.store.put(chunk);
	}

	await tx.done;
};

const indexChatRecord = async (record: LocalChatRecord) => {
	await replaceKnowledgeChunksForSource(
		'chat',
		record.id,
		buildKnowledgeChunks({
			sourceType: 'chat',
			sourceId: record.id,
			title: record.title,
			content: getChatIndexText(record),
			metadata: { chat_id: record.id }
		})
	);
};

const indexFileRecord = async (record: LocalFileRecord) => {
	await replaceKnowledgeChunksForSource(
		'file',
		record.id,
		buildKnowledgeChunks({
			sourceType: 'file',
			sourceId: record.id,
			title: record.filename ?? record.name,
			content: getFileContent(record),
			metadata: {
				file_id: record.id,
				filename: record.filename,
				content_type: record.content_type
			}
		})
	);
};

const deleteKnowledgeChunksForSource = async (
	sourceType: LocalKnowledgeChunkRecord['source_type'],
	sourceId: string
) => {
	const db = await getDb();
	const existing = await db.getAllFromIndex('knowledge_chunks', 'source_id', sourceId);
	const tx = db.transaction('knowledge_chunks', 'readwrite');
	for (const chunk of existing.filter((chunk) => chunk.source_type === sourceType)) {
		await tx.store.delete(chunk.id);
	}
	await tx.done;
};

export const getLocalSettings = async () => {
	let stored = null;

	try {
		if (typeof localStorage !== 'undefined') {
			stored = JSON.parse(localStorage.getItem('settings') ?? 'null');
		}
	} catch {
		stored = null;
	}

	try {
		const db = await getDb();
		const entry = await db.get('meta', 'settings');
		if (entry?.value) {
			stored = entry.value;
		}
	} catch {
		// localStorage remains the fallback when IndexedDB is unavailable.
	}

	return mergeLocalSettings(stored ?? getDefaultLocalSettings());
};

export const saveLocalSettings = async (ui: Record<string, any>) => {
	const merged = mergeLocalSettings(ui);
	merged.directConnections = await prepareDirectConnectionsForSecureStore(merged.directConnections);

	if (typeof localStorage !== 'undefined') {
		localStorage.setItem('settings', JSON.stringify(merged));
	}

	const db = await getDb();
	await db.put('meta', { key: 'settings', value: merged });
	return { ui: merged };
};

export const createLocalChat = async (chat: Record<string, any>, folderId: string | null = null) => {
	const db = await getDb();
	const timestamp = now();
	const id = chat?.id || uuid();
	const title = deriveTitle(chat);

	const record: LocalChatRecord = normalizeRecord({
		id,
		user_id: 'local-user',
		title,
		chat: {
			...chat,
			id,
			title
		},
		meta: {},
		folder_id: folderId,
		pinned: false,
		archived: false,
		created_at: chat?.created_at ?? timestamp,
		updated_at: chat?.updated_at ?? timestamp
	});

	await db.put('chats', record);
	await indexChatRecord(record);
	return record;
};

export const updateLocalChat = async (id: string, chat: Record<string, any>) => {
	const db = await getDb();
	const existing = await db.get('chats', id);

	if (!existing) {
		return createLocalChat({ ...chat, id }, chat?.folder_id ?? null);
	}

	const title = deriveTitle(chat, existing.title);
	const updated = normalizeRecord({
		...existing,
		title,
		chat: {
			...existing.chat,
			...chat,
			id,
			title
		},
		folder_id: chat?.folder_id ?? existing.folder_id ?? null,
		updated_at: now()
	});

	await db.put('chats', updated);
	await indexChatRecord(updated);
	return updated;
};

export const getLocalChat = async (id: string) => {
	const db = await getDb();
	const record = await db.get('chats', id);
	return record ? normalizeRecord(record) : null;
};

export const listLocalChats = async ({
	page = 1,
	limit = 50,
	archived = false,
	pinned,
	search
}: {
	page?: number | null;
	limit?: number;
	archived?: boolean;
	pinned?: boolean;
	search?: string;
} = {}) => {
	const db = await getDb();
	let records = (await db.getAll('chats')).map(normalizeRecord);

	records = records.filter((record) => (record.archived ?? false) === archived);

	if (typeof pinned === 'boolean') {
		records = records.filter((record) => (record.pinned ?? false) === pinned);
	}

	if (search) {
		const query = search.toLowerCase();
		records = records.filter((record) => {
			const messages = Object.values(record.chat?.history?.messages ?? {})
				.map((message: any) => getTextFromContent(message?.content))
				.join(' ')
				.toLowerCase();
			return record.title.toLowerCase().includes(query) || messages.includes(query);
		});
	}

	records.sort((a, b) => (b.updated_at ?? 0) - (a.updated_at ?? 0));

	if (page === null) {
		return records;
	}

	const start = Math.max(page - 1, 0) * limit;
	return records.slice(start, start + limit);
};

export const importLocalChats = async (chats: any[]) => {
	const db = await getDb();
	const tx = db.transaction('chats', 'readwrite');
	const imported: LocalChatRecord[] = [];

	for (const entry of chats) {
		const sourceChat = entry?.chat ?? entry;
		const id = sourceChat?.id || uuid();
		const timestamp = now();
		const record = normalizeRecord({
			id,
			user_id: 'local-user',
			title: deriveTitle(sourceChat),
			chat: {
				...sourceChat,
				id,
				title: deriveTitle(sourceChat)
			},
			meta: entry?.meta ?? {},
			folder_id: entry?.folder_id ?? null,
			pinned: entry?.pinned ?? false,
			archived: entry?.archived ?? false,
			created_at: entry?.created_at ?? sourceChat?.created_at ?? timestamp,
			updated_at: entry?.updated_at ?? sourceChat?.updated_at ?? timestamp
		});

		imported.push(record);
		await tx.store.put(record);
	}

	await tx.done;
	for (const record of imported) {
		await indexChatRecord(record);
	}
	return imported;
};

export const deleteLocalChat = async (id: string) => {
	const db = await getDb();
	await db.delete('chats', id);
	await deleteKnowledgeChunksForSource('chat', id);
	return true;
};

export const deleteAllLocalChats = async () => {
	const db = await getDb();
	await db.clear('chats');
	const chunks = await db.getAllFromIndex('knowledge_chunks', 'source_type', 'chat');
	const tx = db.transaction('knowledge_chunks', 'readwrite');
	for (const chunk of chunks) {
		await tx.store.delete(chunk.id);
	}
	await tx.done;
	return true;
};

export const archiveLocalChat = async (id: string, archived = true) => {
	const record = await getLocalChat(id);
	if (!record) {
		return null;
	}

	const db = await getDb();
	const updated = normalizeRecord({ ...record, archived, updated_at: now() });
	await db.put('chats', updated);
	return updated;
};

export const archiveAllLocalChats = async (archived = true) => {
	const db = await getDb();
	const records = await db.getAll('chats');
	const tx = db.transaction('chats', 'readwrite');

	for (const record of records) {
		await tx.store.put(normalizeRecord({ ...record, archived, updated_at: now() }));
	}

	await tx.done;
	return true;
};

export const toggleLocalChatPinned = async (id: string) => {
	const record = await getLocalChat(id);
	if (!record) {
		return null;
	}

	const db = await getDb();
	const updated = normalizeRecord({ ...record, pinned: !(record.pinned ?? false), updated_at: now() });
	await db.put('chats', updated);
	return updated;
};

export const updateLocalChatFolder = async (id: string, folderId: string | null = null) => {
	const record = await getLocalChat(id);
	if (!record) {
		return null;
	}

	const db = await getDb();
	const updated = normalizeRecord({ ...record, folder_id: folderId, updated_at: now() });
	await db.put('chats', updated);
	return updated;
};

export const getLocalChatTags = async (id: string) => {
	const record = await getLocalChat(id);
	return record?.chat?.tags ?? [];
};

export const addLocalChatTag = async (id: string, tagName: string) => {
	const record = await getLocalChat(id);
	if (!record) {
		return null;
	}

	const tags = Array.from(new Set([...(record.chat?.tags ?? []), tagName]));
	return updateLocalChat(id, { ...record.chat, tags });
};

export const deleteLocalChatTag = async (id: string, tagName: string) => {
	const record = await getLocalChat(id);
	if (!record) {
		return null;
	}

	const tags = (record.chat?.tags ?? []).filter((tag: string) => tag !== tagName);
	return updateLocalChat(id, { ...record.chat, tags });
};

export const deleteLocalChatTags = async (id: string) => {
	const record = await getLocalChat(id);
	if (!record) {
		return null;
	}

	return updateLocalChat(id, { ...record.chat, tags: [] });
};

export const getAllLocalTags = async () => {
	const records = await listLocalChats({ page: null, archived: false });
	const tags = new Set<string>();

	for (const record of records) {
		for (const tag of record.chat?.tags ?? []) {
			tags.add(tag);
		}
	}

	return Array.from(tags).map((name) => ({ name }));
};

export const saveLocalFileRecord = async (file: Omit<LocalFileRecord, 'created_at' | 'updated_at'>) => {
	const db = await getDb();
	const timestamp = now();
	const record: LocalFileRecord = {
		...file,
		created_at: (file as LocalFileRecord).created_at ?? timestamp,
		updated_at: timestamp
	};
	await db.put('files', record);
	await indexFileRecord(record);
	return record;
};

export const listLocalFiles = async () => {
	const db = await getDb();
	const files = await db.getAll('files');
	return files.sort((a, b) => (b.updated_at ?? 0) - (a.updated_at ?? 0));
};

export const getLocalFileRecord = async (id: string) => {
	const db = await getDb();
	return (await db.get('files', id)) ?? null;
};

export const deleteLocalFileRecord = async (id: string) => {
	const db = await getDb();
	await db.delete('files', id);
	await deleteKnowledgeChunksForSource('file', id);
	return true;
};

export const deleteAllLocalFiles = async () => {
	const db = await getDb();
	await db.clear('files');
	const chunks = await db.getAllFromIndex('knowledge_chunks', 'source_type', 'file');
	const tx = db.transaction('knowledge_chunks', 'readwrite');
	for (const chunk of chunks) {
		await tx.store.delete(chunk.id);
	}
	await tx.done;
	return true;
};

export const searchLocalKnowledge = async ({
	query,
	limit = 8,
	sourceTypes
}: {
	query: string;
	limit?: number;
	sourceTypes?: LocalKnowledgeChunkRecord['source_type'][];
}) => {
	const db = await getDb();
	const chunks = await db.getAll('knowledge_chunks');
	const allowedSources = sourceTypes?.length ? new Set(sourceTypes) : null;

	return chunks
		.map((chunk) => ({
			chunk,
			score: scoreTextMatch(query, `${chunk.title}\n${chunk.content}`)
		}))
		.filter(({ chunk, score }) => score > 0 && (!allowedSources || allowedSources.has(chunk.source_type)))
		.sort((a, b) => b.score - a.score || b.chunk.updated_at - a.chunk.updated_at)
		.slice(0, Math.max(1, Math.min(50, Math.trunc(limit))))
		.map(({ chunk, score }) => ({
			...chunk,
			score
		}));
};

export const createLocalMemory = async ({
	content,
	tags = [],
	metadata = null
}: {
	content: string;
	tags?: string[];
	metadata?: Record<string, any> | null;
}) => {
	const db = await getDb();
	const timestamp = now();
	const record: LocalMemoryRecord = {
		id: uuid(),
		content,
		tags,
		metadata,
		created_at: timestamp,
		updated_at: timestamp
	};
	await db.put('memories', record);
	await replaceKnowledgeChunksForSource(
		'memory',
		record.id,
		buildKnowledgeChunks({
			sourceType: 'memory',
			sourceId: record.id,
			title: tags.length ? tags.join(', ') : 'Memory',
			content,
			metadata: { memory_id: record.id, tags }
		})
	);
	return record;
};

export const searchLocalMemories = async ({ query, limit = 10 }: { query: string; limit?: number }) => {
	const db = await getDb();
	const records = await db.getAll('memories');
	return records
		.map((memory) => ({
			...memory,
			score: scoreTextMatch(query, `${memory.content}\n${memory.tags?.join(' ') ?? ''}`)
		}))
		.filter((memory) => memory.score > 0)
		.sort((a, b) => b.score - a.score || b.updated_at - a.updated_at)
		.slice(0, Math.max(1, Math.min(50, Math.trunc(limit))));
};

export const deleteLocalMemory = async (id: string) => {
	const db = await getDb();
	await db.delete('memories', id);
	await deleteKnowledgeChunksForSource('memory', id);
	return true;
};

export const createLocalNote = async ({
	title,
	content,
	tags = [],
	metadata = null
}: {
	title: string;
	content: string;
	tags?: string[];
	metadata?: Record<string, any> | null;
}) => {
	const db = await getDb();
	const timestamp = now();
	const record: LocalNoteRecord = {
		id: uuid(),
		title,
		content,
		tags,
		metadata,
		created_at: timestamp,
		updated_at: timestamp
	};
	await db.put('notes', record);
	await replaceKnowledgeChunksForSource(
		'note',
		record.id,
		buildKnowledgeChunks({
			sourceType: 'note',
			sourceId: record.id,
			title,
			content,
			metadata: { note_id: record.id, tags }
		})
	);
	return record;
};

export const searchLocalNotes = async ({ query, limit = 10 }: { query: string; limit?: number }) => {
	const db = await getDb();
	const records = await db.getAll('notes');
	return records
		.map((note) => ({
			...note,
			score: scoreTextMatch(query, `${note.title}\n${note.content}\n${note.tags?.join(' ') ?? ''}`)
		}))
		.filter((note) => note.score > 0)
		.sort((a, b) => b.score - a.score || b.updated_at - a.updated_at)
		.slice(0, Math.max(1, Math.min(50, Math.trunc(limit))));
};

export const createLocalTask = async ({
	title,
	description = '',
	status = 'todo',
	due_at = null,
	metadata = null
}: {
	title: string;
	description?: string;
	status?: LocalTaskRecord['status'];
	due_at?: number | null;
	metadata?: Record<string, any> | null;
}) => {
	const db = await getDb();
	const timestamp = now();
	const record: LocalTaskRecord = {
		id: uuid(),
		title,
		description,
		status,
		due_at,
		metadata,
		created_at: timestamp,
		updated_at: timestamp
	};
	await db.put('tasks', record);
	return record;
};

export const updateLocalTask = async (
	id: string,
	updates: Partial<Omit<LocalTaskRecord, 'id' | 'created_at' | 'updated_at'>>
) => {
	const db = await getDb();
	const existing = await db.get('tasks', id);
	if (!existing) {
		return null;
	}

	const record: LocalTaskRecord = {
		...existing,
		...updates,
		updated_at: now()
	};
	await db.put('tasks', record);
	return record;
};

export const listLocalTasks = async ({
	status,
	limit = 50
}: {
	status?: LocalTaskRecord['status'];
	limit?: number;
} = {}) => {
	const db = await getDb();
	let records = await db.getAll('tasks');
	if (status) {
		records = records.filter((task) => task.status === status);
	}

	return records
		.sort((a, b) => {
			const dueDiff = (a.due_at ?? Number.MAX_SAFE_INTEGER) - (b.due_at ?? Number.MAX_SAFE_INTEGER);
			return dueDiff || b.updated_at - a.updated_at;
		})
		.slice(0, Math.max(1, Math.min(200, Math.trunc(limit))));
};
