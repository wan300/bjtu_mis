<script lang="ts">
	import DOMPurify from 'dompurify';
	import { getContext, tick } from 'svelte';
	import type { Writable } from 'svelte/store';
	import type { i18n as i18nType } from 'i18next';
	import { toast } from 'svelte-sonner';
	import { formatFileSize } from '$lib/utils';
	import {
		dedupeNativeAgentGeneratedFiles,
		findNativeAgentGeneratedFile,
		getGeneratedFileSaveErrorMessage,
		getNativeAgentGeneratedFilePreviewKind,
		normalizeNativeAgentWorkspacePath,
		shouldShowNativeAgentOutputEntry
	} from '$lib/local-first/generated-files';
	import {
		readNativeAgentGeneratedFilePreview,
		saveNativeAgentGeneratedFile,
		type NativeAgentGeneratedFile
	} from '$lib/local-first/native-agent-tools';

	import PDFViewer from '$lib/components/common/PDFViewer.svelte';
	import Markdown from '$lib/components/chat/Messages/Markdown.svelte';
	import CodeBlock from '$lib/components/chat/Messages/CodeBlock.svelte';
	import Spinner from '$lib/components/common/Spinner.svelte';
	import ArrowPath from '$lib/components/icons/ArrowPath.svelte';
	import DocumentPage from '$lib/components/icons/DocumentPage.svelte';
	import Download from '$lib/components/icons/Download.svelte';
	import FolderOpen from '$lib/components/icons/FolderOpen.svelte';
	import XMark from '$lib/components/icons/XMark.svelte';

	const i18n: Writable<i18nType> = getContext('i18n');

	export let workspaceId: string | null = null;
	export let files: NativeAgentGeneratedFile[] = [];
	export let loading = false;
	export let onRefresh: () => Promise<void> | void = () => {};

	let busyPath: string | null = null;
	let filesOpen = false;
	let previewOpen = false;
	let previewLoading = false;
	let previewError = '';
	let previewFileName = '';
	let previewRelativePath = '';
	let previewLocation = '';
	let previewKind: 'pdf' | 'docx' | 'markdown' | 'code' | 'text' | null = null;
	let previewText = '';
	let previewDocxHtml = '';
	let previewPdfData: Uint8Array | null = null;

	$: hasWorkspace = shouldShowNativeAgentOutputEntry(workspaceId);
	$: displayFiles = dedupeNativeAgentGeneratedFiles(files);

	const base64ToUint8Array = (base64: string) => {
		const binary = atob(base64);
		const bytes = new Uint8Array(binary.length);
		for (let index = 0; index < binary.length; index += 1) {
			bytes[index] = binary.charCodeAt(index);
		}
		return bytes;
	};

	const getExtension = (path: string) => path.split('.').pop()?.toLowerCase() ?? '';

	const fileNameFromPath = (path: string) => path.split('/').at(-1) ?? path;

	const canPreview = (file: NativeAgentGeneratedFile) =>
		getNativeAgentGeneratedFilePreviewKind(file.relativePath, file.mimeType) !== null;

	const refreshGeneratedFiles = async () => {
		await onRefresh?.();
		await tick();
	};

	const openFilesPanel = async () => {
		if (!hasWorkspace) return;
		filesOpen = true;
		await refreshGeneratedFiles();
	};

	const closeFilesPanel = () => {
		filesOpen = false;
	};

	const resetPreview = () => {
		previewOpen = false;
		previewLoading = false;
		previewError = '';
		previewFileName = '';
		previewRelativePath = '';
		previewLocation = '';
		previewKind = null;
		previewText = '';
		previewDocxHtml = '';
		previewPdfData = null;
	};

	const openPreview = async (file: NativeAgentGeneratedFile, location = file.relativePath) => {
		if (!workspaceId) return false;

		const previewKindForPath = getNativeAgentGeneratedFilePreviewKind(
			file.relativePath,
			file.mimeType
		);
		if (!previewKindForPath) {
			toast.info($i18n.t('Preview is not available for this file.'));
			return false;
		}

		previewOpen = true;
		previewLoading = true;
		previewError = '';
		previewFileName = file.displayName || fileNameFromPath(file.relativePath);
		previewRelativePath = file.relativePath;
		previewLocation = location;
		previewKind = null;
		previewText = '';
		previewDocxHtml = '';
		previewPdfData = null;

		try {
			const preview = await readNativeAgentGeneratedFilePreview({
				workspaceId,
				relativePath: file.relativePath
			});

			if (!preview.previewable) {
				previewOpen = false;
				if (preview.reason === 'too_large') {
					toast.info($i18n.t('Preview skipped because the file is too large.'));
				} else {
					toast.info($i18n.t('Preview is not available for this file.'));
				}
				return false;
			}

			previewKind = preview.kind ?? null;
			previewFileName = preview.displayName ?? file.displayName ?? fileNameFromPath(file.relativePath);

			if (preview.kind === 'pdf' && preview.base64) {
				previewPdfData = base64ToUint8Array(preview.base64);
			} else if (preview.kind === 'docx' && preview.base64) {
				const mammoth = await import('mammoth');
				const result = await mammoth.convertToHtml({
					arrayBuffer: base64ToUint8Array(preview.base64).buffer
				});
				previewDocxHtml = DOMPurify.sanitize(result.value);
			} else {
				previewText = preview.text ?? '';
			}
			return true;
		} catch (error) {
			console.error('Failed to preview generated file:', error);
			previewError = $i18n.t('Failed to preview generated file.');
			return false;
		} finally {
			previewLoading = false;
		}
	};

	const saveGeneratedFile = async (fileOrPath: NativeAgentGeneratedFile | string) => {
		const relativePath =
			typeof fileOrPath === 'string'
				? normalizeNativeAgentWorkspacePath(fileOrPath)
				: fileOrPath.relativePath;
		if (!workspaceId || !relativePath) return false;

		const file =
			typeof fileOrPath === 'string'
				? findNativeAgentGeneratedFile(displayFiles, relativePath)
				: fileOrPath;
		busyPath = relativePath;
		try {
			const saved = await saveNativeAgentGeneratedFile({
				workspaceId,
				relativePath
			});
			const displayName =
				saved.displayName ?? file?.displayName ?? fileNameFromPath(relativePath) ?? relativePath;
			const location = saved.location ?? displayName;

			toast.success($i18n.t('Saved to {{path}}', { path: location }));
			return true;
		} catch (error) {
			console.error('Failed to save generated file:', error);
			const message = getGeneratedFileSaveErrorMessage(error, relativePath);
			toast.error($i18n.t(message.key, message.params));
			return false;
		} finally {
			busyPath = null;
		}
	};

	export const openWorkspacePath = async (value: string) => {
		const relativePath = normalizeNativeAgentWorkspacePath(value);
		if (!workspaceId || !relativePath) return false;

		filesOpen = true;
		await refreshGeneratedFiles();

		const file = findNativeAgentGeneratedFile(displayFiles, relativePath);
		if (!file) {
			toast.error($i18n.t('Generated output file was not found.', { path: relativePath }));
			return true;
		}

		await openPreview(file);
		return true;
	};

	const roleLabel = (role?: string | null) => {
		if (role === 'package') return $i18n.t('Package');
		if (role === 'output') return $i18n.t('Output');
		return role || $i18n.t('File');
	};

	const fileTypeLabel = (file: NativeAgentGeneratedFile) => {
		const kind = getNativeAgentGeneratedFilePreviewKind(file.relativePath, file.mimeType);
		if (kind === 'pdf') return 'PDF';
		if (kind === 'docx') return 'Word';
		const extension = getExtension(file.relativePath);
		return extension ? extension.toUpperCase() : $i18n.t('File');
	};
</script>

<svelte:window
	on:keydown={(event) => {
		if (event.key !== 'Escape') return;
		if (previewOpen) {
			resetPreview();
		} else if (filesOpen) {
			closeFilesPanel();
		}
	}}
/>

{#if hasWorkspace}
	<div class="mx-auto w-full max-w-3xl px-2 pb-2">
		<button
			type="button"
			class="flex w-full items-center gap-3 rounded-lg border border-gray-200 bg-white/90 px-3 py-2 text-left text-xs text-gray-700 shadow-sm transition hover:bg-gray-50 dark:border-gray-800 dark:bg-gray-900/90 dark:text-gray-200 dark:hover:bg-gray-850"
			on:click={openFilesPanel}
			aria-label={$i18n.t('Open output files')}
		>
			<span
				class="inline-flex size-8 shrink-0 items-center justify-center rounded-md bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-200"
			>
				<FolderOpen className="size-4" />
			</span>
			<span class="min-w-0 flex-1">
				<span class="block font-medium">{$i18n.t('Output files')}</span>
				<span class="block truncate text-gray-500 dark:text-gray-400">
					{#if loading}
						{$i18n.t('Refreshing...')}
					{:else}
						{$i18n.t('{{count}} generated', { count: displayFiles.length })}
					{/if}
				</span>
			</span>
			<span class="shrink-0 rounded-md bg-gray-100 px-2 py-1 text-gray-600 dark:bg-gray-800 dark:text-gray-300">
				{displayFiles.length}
			</span>
		</button>
	</div>
{/if}

{#if filesOpen}
	<div class="fixed inset-0 z-[70] text-gray-900 dark:text-gray-50" role="dialog" aria-modal="true">
		<button
			type="button"
			class="absolute inset-0 bg-black/40"
			aria-label={$i18n.t('Close')}
			on:click={closeFilesPanel}
		></button>
		<div
			class="absolute inset-x-0 bottom-0 flex max-h-[85vh] flex-col rounded-t-2xl bg-white shadow-2xl dark:bg-gray-950 md:left-1/2 md:top-1/2 md:bottom-auto md:max-h-[80vh] md:w-full md:max-w-2xl md:-translate-x-1/2 md:-translate-y-1/2 md:rounded-xl"
		>
			<div class="flex min-h-14 items-center gap-3 border-b border-gray-200 px-3 py-2 dark:border-gray-800">
				<button
					type="button"
					class="inline-flex size-9 items-center justify-center rounded-lg hover:bg-gray-100 dark:hover:bg-gray-850"
					aria-label={$i18n.t('Close')}
					on:click={closeFilesPanel}
				>
					<XMark className="size-5" />
				</button>
				<div class="min-w-0 flex-1">
					<div class="truncate text-sm font-semibold">{$i18n.t('Output files')}</div>
					<div class="truncate text-xs text-gray-500 dark:text-gray-400">
						{#if loading}
							{$i18n.t('Refreshing...')}
						{:else}
							{$i18n.t('{{count}} generated', { count: displayFiles.length })}
						{/if}
					</div>
				</div>
				<button
					type="button"
					class="inline-flex h-9 items-center gap-1.5 rounded-lg px-3 text-xs font-medium hover:bg-gray-100 disabled:cursor-wait disabled:opacity-60 dark:hover:bg-gray-850"
					disabled={loading}
					on:click={refreshGeneratedFiles}
				>
					{#if loading}
						<Spinner className="size-3.5" />
					{:else}
						<ArrowPath className="size-3.5" />
					{/if}
					{$i18n.t('Refresh')}
				</button>
			</div>

			<div class="min-h-0 flex-1 overflow-auto p-3">
				{#if loading && displayFiles.length === 0}
					<div class="flex min-h-40 items-center justify-center">
						<Spinner className="size-5" />
					</div>
				{:else if displayFiles.length === 0}
					<div
						class="flex min-h-40 flex-col items-center justify-center gap-2 text-center text-sm text-gray-500 dark:text-gray-400"
					>
						<FolderOpen className="size-8 opacity-70" />
						<div>{$i18n.t('No output files yet.')}</div>
						<button
							type="button"
							class="rounded-lg border border-gray-200 px-3 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-50 dark:border-gray-800 dark:text-gray-200 dark:hover:bg-gray-900"
							on:click={refreshGeneratedFiles}
						>
							{$i18n.t('Refresh')}
						</button>
					</div>
				{:else}
					<div class="space-y-2">
						{#each displayFiles as file}
							<div
								class="flex flex-col gap-3 rounded-lg border border-gray-200 p-3 dark:border-gray-800 sm:flex-row sm:items-center"
							>
								<button
									type="button"
									class="min-w-0 flex flex-1 items-start gap-3 text-left disabled:cursor-default"
									disabled={!canPreview(file)}
									on:click={() => openPreview(file)}
								>
									<span
										class="mt-0.5 inline-flex size-8 shrink-0 items-center justify-center rounded-md bg-gray-100 text-gray-700 dark:bg-gray-900 dark:text-gray-200"
									>
										<DocumentPage className="size-4" />
									</span>
									<span class="min-w-0 flex-1">
										<span class="block truncate text-sm font-medium">{file.displayName}</span>
										<span class="block truncate text-xs text-gray-500 dark:text-gray-400">
											{file.relativePath}
										</span>
										<span class="mt-1 flex flex-wrap gap-1.5 text-[11px] text-gray-500 dark:text-gray-400">
											<span class="rounded bg-gray-100 px-1.5 py-0.5 dark:bg-gray-900">
												{fileTypeLabel(file)}
											</span>
											<span class="rounded bg-gray-100 px-1.5 py-0.5 dark:bg-gray-900">
												{roleLabel(file.role)}
											</span>
											{#if file.sizeBytes}
												<span class="rounded bg-gray-100 px-1.5 py-0.5 dark:bg-gray-900">
													{formatFileSize(file.sizeBytes)}
												</span>
											{/if}
										</span>
									</span>
								</button>
								<div class="flex shrink-0 items-center gap-2 self-end sm:self-center">
									{#if canPreview(file)}
										<button
											type="button"
											class="inline-flex h-9 items-center rounded-lg border border-gray-200 px-3 text-xs font-medium hover:bg-gray-50 dark:border-gray-800 dark:hover:bg-gray-900"
											on:click={() => openPreview(file)}
										>
											{$i18n.t('Preview')}
										</button>
									{/if}
									<button
										type="button"
										class="inline-flex h-9 items-center gap-1.5 rounded-lg bg-gray-900 px-3 text-xs font-medium text-white hover:bg-gray-800 disabled:cursor-wait disabled:opacity-70 dark:bg-gray-100 dark:text-gray-950 dark:hover:bg-gray-200"
										disabled={busyPath === file.relativePath}
										on:click={() => saveGeneratedFile(file)}
									>
										{#if busyPath === file.relativePath}
											<Spinner className="size-3.5" />
										{:else}
											<Download className="size-3.5" />
										{/if}
										{$i18n.t('Save')}
									</button>
								</div>
							</div>
						{/each}
					</div>
				{/if}
			</div>
		</div>
	</div>
{/if}

{#if previewOpen}
	<div
		class="fixed inset-0 z-[80] flex flex-col bg-white text-gray-900 dark:bg-gray-950 dark:text-gray-50"
		role="dialog"
		aria-modal="true"
	>
		<div class="flex min-h-14 items-center gap-3 border-b border-gray-200 px-3 py-2 dark:border-gray-800">
			<button
				type="button"
				class="inline-flex size-9 items-center justify-center rounded-lg hover:bg-gray-100 dark:hover:bg-gray-850"
				aria-label={$i18n.t('Close')}
				on:click={resetPreview}
			>
				<XMark className="size-5" />
			</button>
			<div class="min-w-0 flex-1">
				<div class="truncate text-sm font-semibold">{previewFileName}</div>
				<div class="truncate text-xs text-gray-500 dark:text-gray-400">
					{previewLocation || previewRelativePath}
				</div>
			</div>
			<button
				type="button"
				class="inline-flex h-9 items-center gap-1.5 rounded-lg px-3 text-xs font-medium hover:bg-gray-100 disabled:cursor-wait disabled:opacity-60 dark:hover:bg-gray-850"
				disabled={!previewRelativePath || busyPath === previewRelativePath}
				on:click={() => saveGeneratedFile(previewRelativePath)}
			>
				{#if busyPath === previewRelativePath}
					<Spinner className="size-3.5" />
				{:else}
					<Download className="size-3.5" />
				{/if}
				{$i18n.t('Save')}
			</button>
		</div>

		<div class="min-h-0 flex-1 overflow-hidden">
			{#if previewLoading}
				<div class="flex h-full items-center justify-center">
					<Spinner className="size-5" />
				</div>
			{:else if previewError}
				<div class="p-4 text-sm text-red-600 dark:text-red-300">{previewError}</div>
			{:else if previewKind === 'pdf' && previewPdfData}
				<PDFViewer data={previewPdfData} className="h-full w-full" />
			{:else if previewKind === 'docx'}
				<div class="h-full overflow-auto p-4">
					<div class="office-preview prose max-w-full text-sm dark:prose-invert">
						{@html previewDocxHtml || $i18n.t('No content available')}
					</div>
				</div>
			{:else if previewKind === 'markdown'}
				<div class="h-full overflow-auto p-4 text-sm prose max-w-full dark:prose-invert">
					<Markdown id="native-agent-generated-markdown-preview" content={previewText} />
				</div>
			{:else if previewKind === 'code'}
				<div class="h-full overflow-auto p-4">
					<CodeBlock
						id="native-agent-generated-code-preview"
						token={null}
						code={previewText}
						lang={getExtension(previewRelativePath)}
						edit={false}
						run={false}
						save={false}
						preview={false}
					/>
				</div>
			{:else}
				<pre class="h-full overflow-auto whitespace-pre-wrap break-words p-4 text-sm">{previewText}</pre>
			{/if}
		</div>
	</div>
{/if}
