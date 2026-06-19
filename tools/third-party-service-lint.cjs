#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');

const TEXT_EXTENSIONS = new Set(['.html', '.js', '.mjs', '.css', '.json', '.txt']);
const IGNORED_REFERENCE_ORIGINS = new Set(['http://www.w3.org']);

function main() {
  const target = process.argv[2];
  if (!target || target === '-h' || target === '--help') {
    console.log('Usage: node tools/third-party-service-lint.cjs <plugin-root>');
    process.exit(target ? 0 : 1);
  }

  const root = path.resolve(process.cwd(), target);
  const repoRoot = path.resolve(__dirname, '..');
  const result = { errors: [], warnings: [] };
  const manifestSchema = loadManifestSchema(repoRoot, result);
  lintPlugin(root, result, manifestSchema.permissions);

  result.warnings.forEach((warning) => console.warn(`WARN  ${warning}`));
  result.errors.forEach((error) => console.error(`ERROR ${error}`));
  if (result.errors.length > 0) {
    console.error(`\nBJTU service lint failed: ${result.errors.length} error(s), ${result.warnings.length} warning(s).`);
    process.exit(1);
  }
  console.log(`BJTU service lint passed: ${result.warnings.length} warning(s).`);
}

function lintPlugin(root, result, knownPermissions) {
  const manifestPath = path.join(root, 'bjtu-service.json');
  if (!fs.existsSync(manifestPath)) {
    result.errors.push('Missing bjtu-service.json at plugin root.');
    return result;
  }
  const manifest = readJson(manifestPath, result);
  if (!manifest) return result;

  if (manifest.schema_version !== 1) result.errors.push('schema_version must be 1.');
  if (!/^[a-z][a-z0-9_.-]{2,63}$/.test(stringField(manifest, 'id'))) {
    result.errors.push('id must be 3-64 chars, start with a lowercase letter, and contain only lowercase letters, digits, dot, underscore, or hyphen.');
  }
  ['name', 'description', 'version', 'author'].forEach((field) => {
    if (!stringField(manifest, field).trim()) result.errors.push(`${field} must be a non-empty string.`);
  });

  const entrypoint = validateAssetPath(manifest.entrypoint, 'entrypoint', result);
  const icon = validateAssetPath(manifest.icon, 'icon', result);
  const dist = path.join(root, 'dist');
  if (!fs.existsSync(dist) || !fs.statSync(dist).isDirectory()) {
    result.errors.push('Missing dist/ directory.');
  } else {
    if (entrypoint && !fs.existsSync(path.join(dist, entrypoint))) result.errors.push(`entrypoint does not exist in dist/: ${entrypoint}`);
    if (icon && !fs.existsSync(path.join(dist, icon))) result.errors.push(`icon does not exist in dist/: ${icon}`);
  }

  const permissions = manifest.permissions || {};
  const required = normalizeStringArray(permissions.required, 'permissions.required', result);
  const optional = normalizeStringArray(permissions.optional, 'permissions.optional', result);
  const duplicated = required.filter((id) => optional.includes(id));
  if (duplicated.length) result.errors.push(`Permissions cannot be both required and optional: ${[...new Set(duplicated)].join(', ')}`);
  if (knownPermissions.length) {
    required.concat(optional).forEach((id) => {
      if (!knownPermissions.includes(id)) result.errors.push(`Unknown permission: ${id}`);
    });
  }

  const allowedOrigins = normalizeStringArray(manifest.allowed_origins, 'allowed_origins', result).map((origin) =>
    normalizeOrigin(origin, result)
  ).filter(Boolean);
  if (allowedOrigins.length !== new Set(allowedOrigins).size) result.errors.push('allowed_origins contains duplicates.');

  if (fs.existsSync(dist) && fs.statSync(dist).isDirectory()) {
    scanExternalOrigins(dist, new Set(allowedOrigins), result);
  }

  return result;
}

function loadManifestSchema(repoRoot, result) {
  const docsSchema = path.join(repoRoot, 'docs', 'third-party-service-manifest.schema.json');
  const webSchema = path.join(repoRoot, 'web', 'assets', 'schemas', 'third-party-service-manifest.schema.json');
  if (!fs.existsSync(docsSchema) || !fs.existsSync(webSchema)) {
    result.errors.push('Missing docs/ or web/assets/schemas/ manifest schema.');
    return { permissions: [] };
  }
  const docsText = fs.readFileSync(docsSchema, 'utf8');
  const webText = fs.readFileSync(webSchema, 'utf8');
  if (docsText !== webText) result.errors.push('docs/ and web/assets/schemas/ manifest schemas are not identical.');
  const schema = readJson(docsSchema, result);
  const schemaPermissions = schema?.$defs?.permission_array?.items?.enum;
  if (!Array.isArray(schemaPermissions)) {
    result.errors.push('Manifest schema is missing permission enum.');
    return { permissions: [] };
  }

  const permissions = schemaPermissions
    .map((item) => (typeof item === 'string' ? item.trim() : ''))
    .filter(Boolean);
  if (permissions.length !== schemaPermissions.length) {
    result.errors.push('Manifest schema permission enum must contain only non-empty strings.');
  }
  if (permissions.length !== new Set(permissions).size) {
    result.errors.push('Manifest schema permission enum contains duplicates.');
  }

  return { permissions };
}

function readJson(file, result) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    result.errors.push(`Invalid JSON in ${file}: ${error.message}`);
    return null;
  }
}

function stringField(object, field) {
  return typeof object[field] === 'string' ? object[field] : '';
}

function normalizeStringArray(value, field, result) {
  if (!Array.isArray(value)) {
    result.errors.push(`${field} must be an array.`);
    return [];
  }
  return value.map((item) => (typeof item === 'string' ? item.trim() : '')).filter(Boolean);
}

function validateAssetPath(value, field, result) {
  if (typeof value !== 'string' || !value.trim()) {
    result.errors.push(`${field} must be a non-empty relative path.`);
    return '';
  }
  const raw = value.trim();
  const normalized = raw.replace(/\\/g, '/').split('/').filter(Boolean).join('/');
  if (raw.startsWith('/') || raw.includes('\\') || raw.includes(':') || normalized.split('/').includes('..')) {
    result.errors.push(`${field} must be a relative path inside dist/ and cannot contain "..", backslash, URL, drive prefix, or absolute path.`);
  }
  return normalized;
}

function normalizeOrigin(value, result) {
  try {
    const url = new URL(value);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash || (url.pathname && url.pathname !== '/')) {
      result.errors.push(`allowed_origins entry must be an HTTP/HTTPS origin without path/query/userinfo: ${value}`);
      return null;
    }
    return `${url.protocol}//${url.host.toLowerCase()}`;
  } catch {
    result.errors.push(`Invalid allowed_origins entry: ${value}`);
    return null;
  }
}

function scanExternalOrigins(dist, allowedOrigins, result) {
  walk(dist).forEach((file) => {
    if (!TEXT_EXTENSIONS.has(path.extname(file).toLowerCase())) return;
    const content = fs.readFileSync(file, 'utf8');
    const matches = content.matchAll(/https?:\/\/[A-Za-z0-9.-]+(?::\d+)?(?:\/[^\s"'<>)]*)?/g);
    for (const match of matches) {
      const origin = normalizeOriginFromUrl(match[0]);
      if (origin && !allowedOrigins.has(origin) && !IGNORED_REFERENCE_ORIGINS.has(origin)) {
        result.warnings.push(`${path.relative(dist, file)} references undeclared origin ${origin}.`);
      }
    }
  });
}

function normalizeOriginFromUrl(value) {
  try {
    const url = new URL(value);
    return `${url.protocol}//${url.host.toLowerCase()}`;
  } catch {
    return null;
  }
}

function walk(root) {
  const out = [];
  fs.readdirSync(root, { withFileTypes: true }).forEach((entry) => {
    const fullPath = path.join(root, entry.name);
    if (entry.isDirectory()) out.push(...walk(fullPath));
    else if (entry.isFile()) out.push(fullPath);
  });
  return out;
}

main();
