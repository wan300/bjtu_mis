import { WEBUI_API_BASE_URL } from '$lib/constants';
import { isLocalFirstClient } from '$lib/local-first';

export const checkActiveChats = async (token: string, chatIds: string[]) => {
	if (isLocalFirstClient()) {
		return { active_chat_ids: [] };
	}

	const res = await fetch(`${WEBUI_API_BASE_URL}/tasks/active/chats`, {
		method: 'POST',
		headers: {
			'Content-Type': 'application/json',
			Authorization: `Bearer ${token}`
		},
		body: JSON.stringify({ chat_ids: chatIds })
	});
	if (!res.ok) throw await res.json();
	return res.json();
};
