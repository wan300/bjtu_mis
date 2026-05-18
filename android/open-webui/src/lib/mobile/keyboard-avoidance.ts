export type KeyboardAvoidanceCallback = (keyboardHeight: number) => void;

export function createKeyboardAvoidance(callback: KeyboardAvoidanceCallback): () => void {
	const visualViewport = window.visualViewport;
	if (!visualViewport) return () => {};

	const minKeyboardHeight = 64;
	let lastHeight = -1;
	let frame = 0;

	const getKeyboardHeight = () => {
		const viewportBottom = visualViewport.height + visualViewport.offsetTop;
		const height = Math.max(0, Math.round(window.innerHeight - viewportBottom));
		return height >= minKeyboardHeight ? height : 0;
	};

	const notify = () => {
		frame = 0;
		const currentHeight = getKeyboardHeight();
		if (currentHeight !== lastHeight) {
			lastHeight = currentHeight;
			callback(currentHeight);
		}
	};

	const handler = () => {
		if (frame) cancelAnimationFrame(frame);
		frame = requestAnimationFrame(notify);
	};

	visualViewport.addEventListener('resize', handler);
	visualViewport.addEventListener('scroll', handler);
	window.addEventListener('resize', handler);

	handler();

	return () => {
		if (frame) cancelAnimationFrame(frame);
		visualViewport.removeEventListener('resize', handler);
		visualViewport.removeEventListener('scroll', handler);
		window.removeEventListener('resize', handler);
	};
}
