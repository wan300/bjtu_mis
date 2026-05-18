<script lang="ts">
	import { getContext } from 'svelte';
	import type { Writable } from 'svelte/store';
	import type { i18n as i18nType } from 'i18next';
	import { formatFileSize } from '$lib/utils';
	import type {
		NativeAgentAttachment,
		NativeAgentAttachmentFailure
	} from '$lib/local-first/native-agent-tools';
	import {
		HOMEWORK_DRAFT_PREPARING_I18N_KEY,
		getNativeAgentAttachmentExtractionStatus
	} from '$lib/local-first/homework-agent';

	const i18n: Writable<i18nType> = getContext('i18n');

	export let attachments: NativeAgentAttachment[] = [];
	export let failures: NativeAgentAttachmentFailure[] = [];
	export let preparing = false;
</script>

{#if preparing || attachments.length > 0 || failures.length > 0}
	<div class="mx-auto w-full max-w-3xl px-2 pb-2">
		<div
			class="rounded-lg border border-gray-200 bg-white/90 px-3 py-2 text-xs text-gray-700 shadow-sm dark:border-gray-800 dark:bg-gray-900/90 dark:text-gray-200"
		>
			<div class="flex flex-wrap items-center justify-between gap-2">
				<div class="font-medium">{$i18n.t('Homework attachments')}</div>
				{#if preparing}
					<div class="text-gray-500 dark:text-gray-400">
						{$i18n.t(HOMEWORK_DRAFT_PREPARING_I18N_KEY)}
					</div>
				{:else if attachments.length > 0}
					<div class="text-gray-500 dark:text-gray-400">
						{$i18n.t('{{count}} imported', { count: attachments.length })}
					</div>
				{/if}
			</div>

			{#if attachments.length > 0}
				<div class="mt-2 flex flex-wrap gap-1.5">
					{#each attachments as attachment}
						{@const extractionStatus = getNativeAgentAttachmentExtractionStatus(attachment)}
						<span
							class="max-w-full rounded-md border border-gray-200 bg-gray-50 px-2 py-1 text-gray-700 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-200"
							title={`${attachment.displayName} · ${attachment.relativePath}`}
						>
							<span class="font-medium">{attachment.displayName}</span>
							{#if attachment.sizeBytes}
								<span class="ml-1 text-gray-500 dark:text-gray-400">
									{formatFileSize(attachment.sizeBytes)}
								</span>
							{/if}
							{#if extractionStatus.kind === 'extracted'}
								<span class="ml-1 text-emerald-700 dark:text-emerald-300">
									{$i18n.t('{{count}} extracted', { count: extractionStatus.count })}
								</span>
							{:else if extractionStatus.kind === 'failed'}
								<span class="ml-1 text-red-600 dark:text-red-300">
									{$i18n.t('Archive extraction failed')}: {extractionStatus.message}
								</span>
							{/if}
						</span>
					{/each}
				</div>
			{/if}

			{#if failures.length > 0}
				<div class="mt-2 flex flex-col gap-1 text-red-600 dark:text-red-300">
					{#each failures as failure}
						<div>
							{$i18n.t('Import failed')}: {failure.filename}
							{#if failure.message}
								<span class="text-red-500/80 dark:text-red-300/80">({failure.message})</span>
							{/if}
						</div>
					{/each}
				</div>
			{/if}
		</div>
	</div>
{/if}
