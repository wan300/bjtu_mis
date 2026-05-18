<script lang="ts">
	import { copyToClipboard, unescapeHtml } from '$lib/utils';
	import { toast } from 'svelte-sonner';

	import { getContext } from 'svelte';
	import type { Writable } from 'svelte/store';
	import type { i18n as i18nType } from 'i18next';

	const i18n: Writable<i18nType> = getContext('i18n');
	const nativeAgentWorkspacePathClick = getContext<
		((value: string) => boolean | Promise<boolean>) | null
	>('nativeAgentWorkspacePathClick');

	export let token;
	export let done = true;
</script>

<!-- svelte-ignore a11y-click-events-have-key-events -->
<!-- svelte-ignore a11y-no-noninteractive-element-interactions -->
<code
	class="codespan cursor-pointer {!done ? 'fade-in-token' : ''}"
	on:click={async () => {
		const handled = await nativeAgentWorkspacePathClick?.(unescapeHtml(token.text));
		if (handled) {
			return;
		}
		copyToClipboard(unescapeHtml(token.text));
		toast.success($i18n.t('Copied to clipboard'));
	}}>{unescapeHtml(token.text)}</code
>
