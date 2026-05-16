import { WEBUI_API_BASE_URL } from '$lib/constants';
import { isLocalFirstClient } from '$lib/local-first';
import {
	deleteAllLocalFiles,
	deleteLocalFileRecord,
	getLocalFileRecord,
	listLocalFiles,
	saveLocalFileRecord
} from '$lib/local-first/db';
import { extractContentFromFile, splitStream } from '$lib/utils';

const readAsDataURL = (file: File) =>
	new Promise<string>((resolve, reject) => {
		const reader = new FileReader();
		reader.onload = () => resolve(reader.result as string);
		reader.onerror = reject;
		reader.readAsDataURL(file);
	});

const getLocalFileType = (file: File) => {
	if (file.type.startsWith('image/')) return 'image';
	return 'file';
};

const getLocalFileData = async (file: File) => {
	if (file.type.startsWith('image/')) {
		return {
			url: await readAsDataURL(file),
			data: {}
		};
	}

	try {
		return {
			url: undefined,
			data: {
				content: await extractContentFromFile(file)
			}
		};
	} catch (error) {
		return {
			url: undefined,
			data: {
				error: `${error}`
			}
		};
	}
};

export const uploadFile = async (
	token: string,
	file: File,
	metadata?: object | null,
	process?: boolean | null
) => {
	if (isLocalFirstClient()) {
		const { url, data } = await getLocalFileData(file);
		return saveLocalFileRecord({
			id: crypto.randomUUID(),
			user_id: 'local-user',
			filename: file.name,
			name: file.name,
			type: getLocalFileType(file),
			size: file.size,
			content_type: file.type,
			status: data?.error ? 'uploaded' : 'processed',
			data,
			url,
			metadata
		});
	}

	const data = new FormData();
	data.append('file', file);
	if (metadata) {
		data.append('metadata', JSON.stringify(metadata));
	}

	const searchParams = new URLSearchParams();
	if (process !== undefined && process !== null) {
		searchParams.append('process', String(process));
	}

	let error = null;

	const res = await fetch(`${WEBUI_API_BASE_URL}/files/?${searchParams.toString()}`, {
		method: 'POST',
		headers: {
			Accept: 'application/json',
			authorization: `Bearer ${token}`
		},
		body: data
	})
		.then(async (res) => {
			if (!res.ok) throw await res.json();
			return res.json();
		})
		.catch((err) => {
			error = err.detail || err.message;
			console.error(err);
			return null;
		});

	if (error) {
		throw error;
	}

	if (res) {
		const status = await getFileProcessStatus(token, res.id);

		if (status && status.ok) {
			const reader = status.body
				.pipeThrough(new TextDecoderStream())
				.pipeThrough(splitStream('\n'))
				.getReader();

			while (true) {
				const { value, done } = await reader.read();
				if (done) {
					break;
				}

				try {
					let lines = value.split('\n');

					for (const line of lines) {
						if (line !== '') {
							console.log(line);
							if (line === 'data: [DONE]') {
								console.log(line);
							} else {
								let data = JSON.parse(line.replace(/^data: /, ''));
								console.log(data);

								if (data?.error) {
									console.error(data.error);
									res.error = data.error;
								}

								if (res?.data) {
									res.data = data;
								}
							}
						}
					}
				} catch (error) {
					console.log(error);
				}
			}
		}
	}

	if (error) {
		throw error;
	}

	return res;
};

export const getFileProcessStatus = async (token: string, id: string) => {
	if (isLocalFirstClient()) {
		return new Response('data: [DONE]\n', { status: 200 });
	}

	const queryParams = new URLSearchParams();
	queryParams.append('stream', 'true');

	let error = null;
	const res = await fetch(`${WEBUI_API_BASE_URL}/files/${id}/process/status?${queryParams}`, {
		method: 'GET',
		headers: {
			Accept: 'application/json',
			authorization: `Bearer ${token}`
		}
	}).catch((err) => {
		error = err.detail;
		console.error(err);
		return null;
	});

	if (error) {
		throw error;
	}

	return res;
};

export const uploadDir = async (token: string) => {
	let error = null;

	const res = await fetch(`${WEBUI_API_BASE_URL}/files/upload/dir`, {
		method: 'POST',
		headers: {
			Accept: 'application/json',
			authorization: `Bearer ${token}`
		}
	})
		.then(async (res) => {
			if (!res.ok) throw await res.json();
			return res.json();
		})
		.catch((err) => {
			error = err.detail;
			return null;
		});

	if (error) {
		throw error;
	}

	return res;
};

export const getFiles = async (token: string = '') => {
	if (isLocalFirstClient()) {
		return listLocalFiles();
	}

	let error = null;

	const res = await fetch(`${WEBUI_API_BASE_URL}/files/`, {
		method: 'GET',
		headers: {
			Accept: 'application/json',
			'Content-Type': 'application/json',
			authorization: `Bearer ${token}`
		}
	})
		.then(async (res) => {
			if (!res.ok) throw await res.json();
			return res.json();
		})
		.then((json) => {
			return json;
		})
		.catch((err) => {
			error = err.detail;
			console.error(err);
			return null;
		});

	if (error) {
		throw error;
	}

	return res;
};

export const searchFiles = async (
	token: string,
	filename: string = '*',
	skip: number = 0,
	limit: number = 50
) => {
	if (isLocalFirstClient()) {
		const files = await listLocalFiles();
		const query = filename === '*' ? '' : filename.toLowerCase();
		return files
			.filter((file) => !query || file.filename.toLowerCase().includes(query))
			.slice(skip, skip + limit);
	}

	let error = null;

	const searchParams = new URLSearchParams();
	searchParams.append('filename', filename);
	searchParams.append('skip', String(skip));
	searchParams.append('limit', String(limit));

	const res = await fetch(`${WEBUI_API_BASE_URL}/files/search?${searchParams.toString()}`, {
		method: 'GET',
		headers: {
			Accept: 'application/json',
			'Content-Type': 'application/json',
			authorization: `Bearer ${token}`
		}
	})
		.then(async (res) => {
			if (!res.ok) throw await res.json();
			return res.json();
		})
		.catch((err) => {
			error = err.detail;
			console.error(err);
			return [];
		});

	if (error) {
		throw error;
	}

	return res;
};

export const getFileById = async (token: string, id: string) => {
	if (isLocalFirstClient()) {
		return getLocalFileRecord(id);
	}

	let error = null;

	const res = await fetch(`${WEBUI_API_BASE_URL}/files/${id}`, {
		method: 'GET',
		headers: {
			Accept: 'application/json',
			'Content-Type': 'application/json',
			authorization: `Bearer ${token}`
		}
	})
		.then(async (res) => {
			if (!res.ok) throw await res.json();
			return res.json();
		})
		.then((json) => {
			return json;
		})
		.catch((err) => {
			error = err.detail;
			console.error(err);
			return null;
		});

	if (error) {
		throw error;
	}

	return res;
};

export const updateFileDataContentById = async (token: string, id: string, content: string) => {
	if (isLocalFirstClient()) {
		const file = await getLocalFileRecord(id);
		if (!file) return null;
		return saveLocalFileRecord({
			...file,
			data: {
				...(file.data ?? {}),
				content
			}
		});
	}

	let error = null;

	const res = await fetch(`${WEBUI_API_BASE_URL}/files/${id}/data/content/update`, {
		method: 'POST',
		headers: {
			Accept: 'application/json',
			'Content-Type': 'application/json',
			authorization: `Bearer ${token}`
		},
		body: JSON.stringify({
			content: content
		})
	})
		.then(async (res) => {
			if (!res.ok) throw await res.json();
			return res.json();
		})
		.then((json) => {
			return json;
		})
		.catch((err) => {
			error = err.detail;
			console.error(err);
			return null;
		});

	if (error) {
		throw error;
	}

	return res;
};

export const getFileContentById = async (id: string) => {
	if (isLocalFirstClient()) {
		const file = await getLocalFileRecord(id);
		return file?.data?.content ?? file?.url ?? null;
	}

	let error = null;

	const res = await fetch(`${WEBUI_API_BASE_URL}/files/${id}/content`, {
		method: 'GET',
		headers: {
			Accept: 'application/json'
		},
		credentials: 'include'
	})
		.then(async (res) => {
			if (!res.ok) throw await res.json();
			return await res.arrayBuffer();
		})
		.catch((err) => {
			error = err.detail;
			console.error(err);

			return null;
		});

	if (error) {
		throw error;
	}

	return res;
};

export const deleteFileById = async (token: string, id: string) => {
	if (isLocalFirstClient()) {
		return deleteLocalFileRecord(id);
	}

	let error = null;

	const res = await fetch(`${WEBUI_API_BASE_URL}/files/${id}`, {
		method: 'DELETE',
		headers: {
			Accept: 'application/json',
			'Content-Type': 'application/json',
			authorization: `Bearer ${token}`
		}
	})
		.then(async (res) => {
			if (!res.ok) throw await res.json();
			return res.json();
		})
		.then((json) => {
			return json;
		})
		.catch((err) => {
			error = err.detail;
			console.error(err);
			return null;
		});

	if (error) {
		throw error;
	}

	return res;
};

export const deleteAllFiles = async (token: string) => {
	if (isLocalFirstClient()) {
		return deleteAllLocalFiles();
	}

	let error = null;

	const res = await fetch(`${WEBUI_API_BASE_URL}/files/all`, {
		method: 'DELETE',
		headers: {
			Accept: 'application/json',
			'Content-Type': 'application/json',
			authorization: `Bearer ${token}`
		}
	})
		.then(async (res) => {
			if (!res.ok) throw await res.json();
			return res.json();
		})
		.then((json) => {
			return json;
		})
		.catch((err) => {
			error = err.detail;
			console.error(err);
			return null;
		});

	if (error) {
		throw error;
	}

	return res;
};
