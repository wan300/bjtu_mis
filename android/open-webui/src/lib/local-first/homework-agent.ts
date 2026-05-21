import type {
	NativeAgentAttachment,
	NativeAgentAttachmentFailure,
	NativeAgentHomeworkDraft
} from './native-agent-tools';

export const HOMEWORK_DRAFT_PREPARING_I18N_KEY = 'Preparing homework attachments...';
export const HOMEWORK_HANDOFF_EVENT = 'bjtu-mis:homework-handoff';

export type NativeHomeworkDraftState = {
	prompt: string;
	workspaceId: string | null;
	attachments: NativeAgentAttachment[];
	failures: NativeAgentAttachmentFailure[];
	params: Record<string, string>;
};

export type NativeAgentAttachmentExtractionStatus =
	| { kind: 'extracted'; count: number }
	| { kind: 'failed'; message: string }
	| { kind: 'none' };

export const getNativeAgentAttachmentExtractionStatus = (
	attachment: NativeAgentAttachment
): NativeAgentAttachmentExtractionStatus => {
	const count = attachment.extractedFiles?.length ?? 0;
	if (count > 0) {
		return { kind: 'extracted', count };
	}

	const message = attachment.extractionError?.trim();
	if (message) {
		return { kind: 'failed', message };
	}

	return { kind: 'none' };
};

export const shouldBlockHomeworkDraftSubmit = (preparing: boolean) => preparing;

export const createNativeHomeworkDraftState = (
	handoff: NativeAgentHomeworkDraft | null | undefined
): NativeHomeworkDraftState | null => {
	if (!handoff?.hasPending) {
		return null;
	}

	const workspaceId = handoff.workspaceId?.trim() || null;

	return {
		prompt: handoff.draft ?? '',
		workspaceId,
		attachments: handoff.attachments ?? [],
		failures: handoff.failedAttachments ?? [],
		params: workspaceId ? { agent_workspace_id: workspaceId } : {}
	};
};
