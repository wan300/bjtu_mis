import { readFileSync } from 'node:fs';
import { parse } from 'svelte/compiler';
import { describe, expect, it } from 'vitest';
import { MODEL_SELECTOR_LAYER, MODEL_SELECTOR_MENU_LAYER } from './layers';

const component = (path) =>
	parse(readFileSync(new URL(path, import.meta.url), 'utf8'), { modern: true });

function nodes(value, predicate) {
	if (!value || typeof value !== 'object') return [];
	return [
		...(predicate(value) ? [value] : []),
		...Object.values(value).flatMap((child) => nodes(child, predicate))
	];
}

describe('model selector portal layers', () => {
	it('keeps nested actions above the selector and preserves its toast priority', () => {
		expect(MODEL_SELECTOR_LAYER).toBe(1_000_000_000);
		expect(MODEL_SELECTOR_MENU_LAYER).toBeGreaterThan(MODEL_SELECTOR_LAYER);
	});

	it('binds the selector layer into its portaled element style', () => {
		const styles = nodes(
			component('./Selector.svelte'),
			(node) => node.type === 'Attribute' && node.name === 'style'
		);
		expect(
			styles.some(
				(style) =>
					nodes(
						style.value,
						(node) => node.type === 'Identifier' && node.name === 'MODEL_SELECTOR_LAYER'
					).length > 0
			)
		).toBe(true);
	});

	it('passes the higher menu layer to the independent Dropdown portal', () => {
		const dropdown = nodes(
			component('./ModelItemMenu.svelte'),
			(node) => node.type === 'Component' && node.name === 'Dropdown'
		)[0];
		expect(dropdown.attributes.find((attr) => attr.name === 'zIndex')?.value.expression.name).toBe(
			'MODEL_SELECTOR_MENU_LAYER'
		);
	});

	it('keeps other dropdowns at the existing default and applies explicit overrides', () => {
		const dropdown = component('../../common/Dropdown.svelte');
		const declaration = nodes(
			dropdown.instance,
			(node) => node.type === 'VariableDeclarator' && node.id?.name === 'zIndex'
		)[0];
		expect(declaration.init.value).toBe(9999);
		const assignment = nodes(
			dropdown.instance,
			(node) => node.type === 'AssignmentExpression' && node.left?.property?.name === 'zIndex'
		)[0];
		expect(assignment.right.callee.name).toBe('String');
		expect(assignment.right.arguments[0].name).toBe('zIndex');
	});

	it('closes the high selector layer before showing the delete confirmation', () => {
		const handler = nodes(
			component('./Selector.svelte').instance,
			(node) => node.type === 'VariableDeclarator' && node.id?.name === 'deleteModelHandler'
		)[0];
		const assignments = handler.init.body.body
			.filter((node) => node.expression?.type === 'AssignmentExpression')
			.map((node) => node.expression);
		const closeIndex = assignments.findIndex(
			(node) => node.left.name === 'show' && node.right.value === false
		);
		const confirmIndex = assignments.findIndex(
			(node) => node.left.name === 'showDeleteConfirm' && node.right.value === true
		);
		expect(closeIndex).toBeGreaterThanOrEqual(0);
		expect(confirmIndex).toBeGreaterThan(closeIndex);
	});
});
