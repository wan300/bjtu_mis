<script lang="ts">
	import { toast } from 'svelte-sonner';
	import { createEventDispatcher, onMount, getContext, tick } from 'svelte';
	import { getModels as _getModels } from '$lib/apis';
	import { isLocalFirstClient } from '$lib/local-first';
	import { normalizeLocalWebSearchSettings } from '$lib/local-first/web-search-config';

	const dispatch = createEventDispatcher();
	const i18n = getContext('i18n');

	import { models, settings, user } from '$lib/stores';

	import Switch from '$lib/components/common/Switch.svelte';
	import Spinner from '$lib/components/common/Spinner.svelte';
	import Tooltip from '$lib/components/common/Tooltip.svelte';
	import Plus from '$lib/components/icons/Plus.svelte';
	import Connection from './Connections/Connection.svelte';

	import AddConnectionModal from '$lib/components/AddConnectionModal.svelte';

	export let saveSettings: Function;

	type DirectConnectionsConfig = {
		OPENAI_API_BASE_URLS: string[];
		OPENAI_API_KEYS: string[];
		OPENAI_API_KEY_REFS?: string[];
		OPENAI_API_CONFIGS: Record<string | number, Record<string, any>>;
	};

	type DirectConnectionInput = {
		url: string;
		key: string;
		config: Record<string, any>;
	};

	let config: DirectConnectionsConfig | null = null;
	let localWebSearch = normalizeLocalWebSearchSettings();

	let showConnectionModal = false;
	const localFirst = isLocalFirstClient();

	const addConnectionHandler = async (connection: DirectConnectionInput) => {
		if (!config) return;

		config.OPENAI_API_BASE_URLS.push(connection.url);
		config.OPENAI_API_KEYS.push(connection.key);
		config.OPENAI_API_CONFIGS[config.OPENAI_API_BASE_URLS.length - 1] = connection.config;

		await updateHandler();
	};

	const updateHandler = async () => {
		if (!config) return;

		// Remove trailing slashes
		config.OPENAI_API_BASE_URLS = config.OPENAI_API_BASE_URLS.map((url: string) =>
			url.replace(/\/$/, '')
		);

		// Check if API KEYS length is same than API URLS length
		if (config.OPENAI_API_KEYS.length !== config.OPENAI_API_BASE_URLS.length) {
			// if there are more keys than urls, remove the extra keys
			if (config.OPENAI_API_KEYS.length > config.OPENAI_API_BASE_URLS.length) {
				config.OPENAI_API_KEYS = config.OPENAI_API_KEYS.slice(
					0,
					config.OPENAI_API_BASE_URLS.length
				);
			}

			// if there are more urls than keys, add empty keys
			if (config.OPENAI_API_KEYS.length < config.OPENAI_API_BASE_URLS.length) {
				const diff = config.OPENAI_API_BASE_URLS.length - config.OPENAI_API_KEYS.length;
				for (let i = 0; i < diff; i++) {
					config.OPENAI_API_KEYS.push('');
				}
			}
		}
		config.OPENAI_API_KEY_REFS = (config.OPENAI_API_KEY_REFS ?? []).slice(
			0,
			config.OPENAI_API_BASE_URLS.length
		);

		await saveSettings({
			directConnections: config,
			...(localFirst
				? {
						localWebSearch: normalizeLocalWebSearchSettings(localWebSearch)
					}
				: {})
		});
	};

	onMount(async () => {
		config = ($settings?.directConnections as DirectConnectionsConfig | null | undefined) ?? {
			OPENAI_API_BASE_URLS: [],
			OPENAI_API_KEYS: [],
			OPENAI_API_CONFIGS: {}
		};
		localWebSearch = normalizeLocalWebSearchSettings($settings?.localWebSearch ?? {});
	});
</script>

<AddConnectionModal direct bind:show={showConnectionModal} onSubmit={addConnectionHandler} />

<form
	id="tab-connections"
	class="flex flex-col h-full justify-between text-sm"
	on:submit|preventDefault={() => {
		updateHandler();
	}}
>
	<div class=" overflow-y-scroll scrollbar-hidden h-full">
		{#if config !== null}
			<div class="">
				<div class="pr-1.5">
					<div class="">
						<div class="flex justify-between items-center mb-0.5">
							<div class="font-medium">{$i18n.t('Manage Direct Connections')}</div>

							<Tooltip content={$i18n.t(`Add Connection`)}>
								<button
									class="px-1"
									aria-label={$i18n.t('Add Connection')}
									on:click={() => {
										showConnectionModal = true;
									}}
									type="button"
								>
									<Plus />
								</button>
							</Tooltip>
						</div>

						<div class="flex flex-col gap-1.5">
							{#each config?.OPENAI_API_BASE_URLS ?? [] as url, idx}
								<Connection
									bind:url
									bind:key={config.OPENAI_API_KEYS[idx]}
									bind:config={config.OPENAI_API_CONFIGS[idx]}
									onSubmit={() => {
										updateHandler();
									}}
									onDelete={() => {
										if (!config) return;
										const currentConfig = config;

										currentConfig.OPENAI_API_BASE_URLS = currentConfig.OPENAI_API_BASE_URLS.filter(
											(url: string, urlIdx: number) => idx !== urlIdx
										);
											currentConfig.OPENAI_API_KEYS = currentConfig.OPENAI_API_KEYS.filter(
												(key: string, keyIdx: number) => idx !== keyIdx
											);
											currentConfig.OPENAI_API_KEY_REFS = (
												currentConfig.OPENAI_API_KEY_REFS ?? []
											).filter((key: string, keyIdx: number) => idx !== keyIdx);

										let newConfig: DirectConnectionsConfig['OPENAI_API_CONFIGS'] = {};
										currentConfig.OPENAI_API_BASE_URLS.forEach((url: string, newIdx: number) => {
											newConfig[newIdx] =
												currentConfig.OPENAI_API_CONFIGS[newIdx < idx ? newIdx : newIdx + 1];
										});
										currentConfig.OPENAI_API_CONFIGS = newConfig;
										config = currentConfig;
									}}
								/>
							{/each}
						</div>
					</div>

					<div class="my-1.5">
						<div
							class="text-xs {($settings?.highContrastMode ?? false)
								? 'text-gray-800 dark:text-gray-100'
								: 'text-gray-500'}"
						>
							{$i18n.t('Connect to your own OpenAI compatible API endpoints.')}
							<br />
							{#if localFirst}
								{$i18n.t('Requests are sent from this device through the native HTTP bridge.')}
							{:else}
								{$i18n.t(
									'CORS must be properly configured by the provider to allow requests from Open WebUI.'
								)}
							{/if}
						</div>
					</div>

					{#if localFirst}
						<div class="mt-5">
							<div class="font-medium mb-1">{$i18n.t('Local Web Search')}</div>
							<div
								class="text-xs mb-2 {($settings?.highContrastMode ?? false)
									? 'text-gray-800 dark:text-gray-100'
									: 'text-gray-500'}"
							>
								{$i18n.t(
									'Search runs on this Android device with automatic DuckDuckGo/Bing fallback.'
								)}
							</div>

							<div class="grid grid-cols-2 gap-2">
								<label class="flex flex-col gap-1 text-xs">
									<span>{$i18n.t('Results')}</span>
									<input
										class="w-full rounded-lg bg-transparent border border-gray-100 dark:border-gray-800 px-3 py-1.5 outline-hidden"
										type="number"
										min="1"
										max="10"
										bind:value={localWebSearch.resultCount}
									/>
								</label>

								<label class="flex flex-col gap-1 text-xs">
									<span>{$i18n.t('Pages to Fetch')}</span>
									<input
										class="w-full rounded-lg bg-transparent border border-gray-100 dark:border-gray-800 px-3 py-1.5 outline-hidden"
										type="number"
										min="0"
										max="5"
										bind:value={localWebSearch.fetchPageCount}
									/>
								</label>

								<label class="flex flex-col gap-1 text-xs">
									<span>{$i18n.t('Max Page Characters')}</span>
									<input
										class="w-full rounded-lg bg-transparent border border-gray-100 dark:border-gray-800 px-3 py-1.5 outline-hidden"
										type="number"
										min="1000"
										max="50000"
										step="1000"
										bind:value={localWebSearch.maxPageChars}
									/>
								</label>

								<label class="flex flex-col gap-1 text-xs">
									<span>{$i18n.t('Timeout (ms)')}</span>
									<input
										class="w-full rounded-lg bg-transparent border border-gray-100 dark:border-gray-800 px-3 py-1.5 outline-hidden"
										type="number"
										min="1000"
										max="60000"
										step="1000"
										bind:value={localWebSearch.timeoutMs}
									/>
								</label>
							</div>
						</div>
					{/if}
				</div>
			</div>
		{:else}
			<div class="flex h-full justify-center">
				<div class="my-auto">
					<Spinner className="size-6" />
				</div>
			</div>
		{/if}
	</div>

	<div class="flex justify-end pt-3 text-sm font-medium">
		<button
			class="px-3.5 py-1.5 text-sm font-medium bg-black hover:bg-gray-900 text-white dark:bg-white dark:text-black dark:hover:bg-gray-100 transition rounded-full"
			type="submit"
		>
			{$i18n.t('Save')}
		</button>
	</div>
</form>
