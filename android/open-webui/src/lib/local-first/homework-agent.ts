import type { NativeAgentAttachment } from './native-agent-tools';

export const HOMEWORK_DRAFT_PREPARING_I18N_KEY = 'Preparing homework attachments...';

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
