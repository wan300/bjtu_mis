import type { IFilePicker } from './types';

function createFileInput(accept: string, multiple = false): HTMLInputElement {
	const input = document.createElement('input');
	input.type = 'file';
	input.accept = accept;
	input.multiple = multiple;
	input.style.display = 'none';
	document.body.appendChild(input);
	return input;
}

function promptFileSelection(input: HTMLInputElement): Promise<File[]> {
	return new Promise((resolve, reject) => {
		const cleanup = () => {
			input.removeEventListener('change', onChange);
			input.removeEventListener('cancel', onCancel);
			input.remove();
		};

		const onChange = () => {
			const files = input.files ? Array.from(input.files) : [];
			cleanup();
			resolve(files);
		};

		const onCancel = () => {
			cleanup();
			reject(new Error('File selection cancelled'));
		};

		input.addEventListener('change', onChange);
		input.addEventListener('cancel', onCancel);
		input.click();
	});
}

export const webFilePicker: IFilePicker = {
	async pickImage(source: 'camera' | 'gallery' = 'gallery') {
		const capture = source === 'camera' ? 'environment' : undefined;
		const input = createFileInput('image/*');
		if (capture) {
			input.setAttribute('capture', capture);
		}
		return promptFileSelection(input);
	},

	async pickFiles(accept = '*/*') {
		const input = createFileInput(accept, true);
		return promptFileSelection(input);
	},

	isSupported() {
		return true;
	}
};
