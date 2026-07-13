import { describe, expect, it } from 'vitest';

import { upsertLocalWheelPackage } from './pyodide-lock-utils.js';

describe('upsertLocalWheelPackage', () => {
	it('replaces a frozen remote URL with the downloaded local wheel', () => {
		const packages = {
			black: {
				name: 'black',
				version: '26.5.1',
				file_name: 'https://files.pythonhosted.org/black.whl',
				imports: [],
				depends: ['click', 'pathspec']
			}
		};

		const key = upsertLocalWheelPackage(packages, {
			requestedName: 'black',
			version: '26.5.1',
			fileName: 'black-26.5.1-py3-none-any.whl',
			sha256: 'black-sha'
		});

		expect(key).toBe('black');
		expect(packages.black).toMatchObject({
			file_name: 'black-26.5.1-py3-none-any.whl',
			sha256: 'black-sha',
			package_type: 'package',
			imports: ['black'],
			depends: ['click', 'pathspec']
		});
	});

	it('deduplicates equivalent hyphen and underscore package keys', () => {
		const packages = {
			'mypy-extensions': {
				name: 'mypy_extensions',
				file_name: 'https://files.pythonhosted.org/mypy_extensions.whl',
				imports: [],
				depends: ['typing-extensions']
			},
			mypy_extensions: {
				name: 'mypy_extensions',
				file_name: 'mypy_extensions-1.1.0-py3-none-any.whl',
				imports: ['mypy_extensions'],
				depends: []
			}
		};

		const key = upsertLocalWheelPackage(packages, {
			requestedName: 'mypy_extensions',
			version: '1.1.0',
			fileName: 'mypy_extensions-1.1.0-py3-none-any.whl',
			sha256: 'mypy-sha'
		});

		expect(key).toBe('mypy-extensions');
		expect(Object.keys(packages)).toEqual(['mypy-extensions']);
		expect(packages['mypy-extensions']).toMatchObject({
			file_name: 'mypy_extensions-1.1.0-py3-none-any.whl',
			imports: ['mypy_extensions'],
			depends: ['typing-extensions']
		});
	});

	it('creates a local entry when the frozen lock has no matching package', () => {
		const packages = {};

		const key = upsertLocalWheelPackage(packages, {
			requestedName: 'pathspec',
			version: '1.1.1',
			fileName: 'pathspec-1.1.1-py3-none-any.whl',
			sha256: 'pathspec-sha'
		});

		expect(key).toBe('pathspec');
		expect(packages.pathspec).toMatchObject({
			name: 'pathspec',
			file_name: 'pathspec-1.1.1-py3-none-any.whl',
			imports: ['pathspec'],
			depends: []
		});
	});
});
