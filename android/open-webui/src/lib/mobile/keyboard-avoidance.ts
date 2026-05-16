export type KeyboardAvoidanceCallback = (keyboardHeight: number) => void;

export function createKeyboardAvoidance(callback: KeyboardAvoidanceCallback): () => void {
	const visualViewport = window.visualViewport;
	if (!visualViewport) return () => {};

	let lastHeight = window.innerHeight - visualViewport.height;

	const handler = () => {
		const currentHeight = window.innerHeight - visualViewport.height;
		if (currentHeight !== lastHeight) {
			lastHeight = currentHeight;
			callback(currentHeight > 0 ? currentHeight : 0);
		}
	};

	visualViewport.addEventListener('resize', handler);
	visualViewport.addEventListener('scroll', handler);

	return () => {
		visualViewport.removeEventListener('resize', handler);
		visualViewport.removeEventListener('scroll', handler);
	};
}
