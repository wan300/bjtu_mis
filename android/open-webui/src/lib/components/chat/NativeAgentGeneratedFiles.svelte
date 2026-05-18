<script lang="ts">
	import DOMPurify from 'dompurify';
	import { getContext } from 'svelte';
	import type { Writable } from 'svelte/store';
	import type { i18n as i18nType } from 'i18next';
	import { toast } from 'svelte-sonner';
	import { formatFileSize } from '$lib/utils';
	import {
		dedupeNativeAgentGeneratedFiles,
		getNativeAgentGeneratedFilePreviewKind,
		normalizeNativeAgentWorkspacePath
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
	import DocumentPage from '$lib/components/icons/DocumentPage.svelte';
	import XMark from '$lib/components/icons/XMark.svelte';

	const i18n: Writable<i18nType> = getContext('i18n');

	export let workspaceId: string | null = null;
	export let files: NativeAgentGeneratedFile[] = [];
	export let loading = false;

	let busyPath: string | null = null;
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

	const openPreview = async ({
		relativePath,
		displayName,
		location
	}: {
		relativePath: string;
		displayName: string;
		location: string;
	}) => {
		if (!workspaceId) return;

		previewOpen = true;
		previewLoading = true;
		previewError = '';
		previewFileName = displayName;
		previewRelativePath = relativePath;
		previewLocation = location;
		previewKind = null;
		previewText = '';
		previewDocxHtml = '';
		previewPdfData = null;

		try {
			const preview = await readNativeAgentGeneratedFilePreview({
				workspaceId,
				relativePath
			});

			if (!preview.previewable) {
				previewOpen = false;
				if (preview.reason === 'too_large') {
					toast.info($i18n.t('File saved. Preview skipped because the file is too large.'));
				}
				return;
			}

			previewKind = preview.kind ?? null;
			previewFileName = preview.displayName ?? displayName;

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
		} catch (error) {
			console.error('Failed to preview generated file:', error);
			previewError = $i18n.t('Failed to preview generated file.');
		} finally {
			previewLoading = false;
		}
	};

	export const openWorkspacePath = async (value: string) => {
		const relativePath = normalizeNativeAgentWorkspacePath(value);
		if (!workspaceId || !relativePath) return false;

		busyPath = relativePath;
		try {
			const saved = await saveNativeAgentGeneratedFile({
				workspaceId,
				relativePath
			});
			const file = displayFiles.find((item) => item.relativePath === relativePath);
			const displayName = saved.displayName ?? file?.displayName ?? relativePath.split('/').at(-1) ?? relativePath;
			const location = saved.location ?? displayName;

			toast.success($i18n.t('Saved to {{path}}', { path: location }));

			const previewKindForPath = getNativeAgentGeneratedFilePreviewKind(
				relativePath,
				file?.mimeType ?? saved.mimeType
			);
			if (previewKindForPath) {
				await openPreview({
					relativePath,
					displayName,
					location
				});
			}

			return true;
		} catch (error) {
			console.error('Failed to save generated file:', error);
			toast.error($i18n.t('Failed to save generated file.'));
			return true;
		} finally {
			busyPath = null;
		}
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
		if (event.key === 'Escape' && previewOpen) {
			resetPreview();
		}
	}}
/>

{#if loading || displayFiles.length > 0}
	<div class="mx-auto w-full max-w-3xl px-2 pb-2">
		<div
			class="rounded-lg border border-gray-200 bg-white/90 px-3 py-2 text-xs text-gray-700 shadow-sm dark:border-gray-800 dark:bg-gray-900/90 dark:text-gray-200"
		>
			<div class="flex flex-wrap items-center justify-between gap-2">
				<div class="font-medium">{$i18n.t('Generated files')}</div>
				<div class="text-gray-500 dark:text-gray-400">
					{#if loading}
						{$i18n.t('Refreshing...')}
					{:else}
						{$i18n.t('{{count}} generated', { count: displayFiles.length })}
					{/if}
				</div>
			</div>

			{#if displayFiles.length > 0}
				<div class="mt-2 flex flex-wrap gap-1.5">
					{#each displayFiles as file}
						<button
							type="button"
							class="flex max-w-full items-center gap-1.5 rounded-md border border-gray-200 bg-gray-50 px-2 py-1 text-left text-gray-700 transition hover:bg-gray-100 disabled:cursor-wait disabled:opacity-70 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-200 dark:hover:bg-gray-850"
							title={`${file.displayName} - ${file.relativePath}`}
							disabled={busyPath === file.relativePath}
							on:click={() => openWorkspacePath(file.relativePath)}
						>
							{#if busyPath === file.relativePath}
								<Spinner className="size-3.5" />
							{:else}
								<DocumentPage className="size-3.5 shrink-0" />
							{/if}
							<span class="min-w-0 truncate font-medium">{file.displayName}</span>
							<span class="shrink-0 text-gray-500 dark:text-gray-400">
								{fileTypeLabel(file)}
							</span>
							{#if file.sizeBytes}
								<span class="shrink-0 text-gray-500 dark:text-gray-400">
									{formatFileSize(file.sizeBytes)}
								</span>
							{/if}
							<span class="shrink-0 text-gray-500 dark:text-gray-400">
								{roleLabel(file.role)}
							</span>
						</button>
					{/each}
				</div>
			{/if}
		</div>
	</div>
{/if}

{#if previewOpen}
	<div
		class="fixed inset-0 z-[80] flex flex-col bg-white text-gray-900 dark:bg-gray-950 dark:text-gray-50"
		role="dialog"
		aria-modal="true"
	>
		<div
			class="flex min-h-14 items-center gap-3 border-b border-gray-200 px-3 py-2 dark:border-gray-800"
		>
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
