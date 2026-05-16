const MIN_TOUCH_TARGET = 44;

export function ensureTouchTarget(el: HTMLElement): boolean {
	const rect = el.getBoundingClientRect();
	return rect.width >= MIN_TOUCH_TARGET && rect.height >= MIN_TOUCH_TARGET;
}

export function applyMinTouchTarget(className = 'min-h-[44px] min-w-[44px]') {
	return className;
}
