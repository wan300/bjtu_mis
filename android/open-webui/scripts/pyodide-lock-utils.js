const canonicalizePackageName = (name) =>
	String(name ?? '')
		.trim()
		.toLowerCase()
		.replace(/[-_.]+/g, '-');

const packageImportName = (name) =>
	String(name ?? '')
		.trim()
		.replace(/[-.]+/g, '_');

const packageDependencies = (entry) => (Array.isArray(entry?.depends) ? entry.depends : []);

export const upsertLocalWheelPackage = (packages, { requestedName, version, fileName, sha256 }) => {
	const canonicalName = canonicalizePackageName(requestedName);
	const importName = packageImportName(requestedName);
	const matchingKeys = Object.keys(packages).filter(
		(key) =>
			canonicalizePackageName(key) === canonicalName ||
			canonicalizePackageName(packages[key]?.name) === canonicalName
	);
	const packageKey =
		matchingKeys.find((key) => key === canonicalName) ?? matchingKeys[0] ?? importName;
	const existing = matchingKeys
		.map((key) => packages[key])
		.filter((entry) => entry && typeof entry === 'object')
		.sort((left, right) => packageDependencies(right).length - packageDependencies(left).length)[0];

	for (const key of matchingKeys) {
		if (key !== packageKey) {
			delete packages[key];
		}
	}

	packages[packageKey] = {
		...(existing ?? {}),
		name: existing?.name || importName,
		version,
		file_name: fileName,
		install_dir: 'site',
		sha256,
		package_type: 'package',
		imports:
			Array.isArray(existing?.imports) && existing.imports.length > 0
				? existing.imports
				: [importName],
		depends: packageDependencies(existing)
	};

	return packageKey;
};
