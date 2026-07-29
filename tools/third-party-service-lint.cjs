#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');

const TEXT_EXTENSIONS = new Set(['.html', '.js', '.mjs', '.css', '.json', '.txt']);
const IGNORED_REFERENCE_ORIGINS = new Set(['http://www.w3.org']);
const SUPPORTED_ICON_EXTENSIONS = new Set(['.svg', '.png', '.webp', '.jpg', '.jpeg']);
const MAX_ICON_BYTES = 1024 * 1024;
const RUNTIME_VERSION = 1;
const CAMPUS_HOSTS = new Set([
  '123.121.147.7',
  'cas.bjtu.edu.cn',
  'mis.bjtu.edu.cn',
  'aa.bjtu.edu.cn',
  'mail.bjtu.edu.cn',
  'zhixing.bjtu.edu.cn',
  'job.bjtu.edu.cn',
  'bksycenter.bjtu.edu.cn'
]);
const MANIFEST_FIELDS = new Set([
  'schema_version', 'id', 'name', 'description', 'version', 'runtime_version',
  'min_runtime_version', 'required_capabilities', 'optional_capabilities',
  'data_schema_version', 'migration_entrypoint', 'entrypoint', 'icon', 'author',
  'permissions', 'connect_origins', 'media_origins', 'frame_origins',
  'navigation_origins', 'bridge_origins', 'marketplace', 'configuration'
]);

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
  lintPlugin(root, result, manifestSchema);

  result.warnings.forEach((warning) => console.warn(`WARN  ${warning}`));
  result.errors.forEach((error) => console.error(`ERROR ${error}`));
  if (result.errors.length > 0) {
    console.error(`\nBJTU service lint failed: ${result.errors.length} error(s), ${result.warnings.length} warning(s).`);
    process.exit(1);
  }
  console.log(`BJTU service lint passed: ${result.warnings.length} warning(s).`);
}

function lintPlugin(root, result, manifestSchema) {
  const manifestPath = path.join(root, 'bjtu-service.json');
  if (!fs.existsSync(manifestPath)) {
    result.errors.push('Missing bjtu-service.json at plugin root.');
    return result;
  }
  const manifest = readJson(manifestPath, result);
  if (!manifest) return result;

  Object.keys(manifest).filter((key) => !MANIFEST_FIELDS.has(key)).forEach((key) => {
    result.errors.push(`Unknown manifest field: ${key}`);
  });
  validateObjectShape(
    manifest.permissions,
    'permissions',
    new Set(['required', 'optional']),
    new Set(['required', 'optional']),
    result
  );
  validateObjectShape(
    manifest.marketplace,
    'marketplace',
    new Set(['category', 'tags', 'license']),
    new Set(['category', 'tags']),
    result
  );
  if (Array.isArray(manifest.configuration)) {
    manifest.configuration.forEach((definition, index) => {
      validateObjectShape(
        definition,
        `configuration[${index}]`,
        new Set(['key', 'label', 'description', 'type', 'required', 'default', 'options']),
        new Set(['key', 'label', 'description', 'type', 'required']),
        result
      );
    });
  }
  if (manifest.schema_version !== 3) result.errors.push('schema_version must be 3.');
  if (!/^[a-z][a-z0-9_.-]{2,63}$/.test(stringField(manifest, 'id'))) {
    result.errors.push('id must be 3-64 chars, start with a lowercase letter, and contain only lowercase letters, digits, dot, underscore, or hyphen.');
  }
  const textLimits = { name: 80, description: 400, version: 40, author: 120 };
  Object.entries(textLimits).forEach(([field, maxLength]) => {
    const value = stringField(manifest, field).trim();
    if (!value) result.errors.push(`${field} must be a non-empty string.`);
    if (value.length > maxLength) result.errors.push(`${field} cannot exceed ${maxLength} characters.`);
  });
  if (!Number.isInteger(manifest.runtime_version) || manifest.runtime_version < 1) {
    result.errors.push('runtime_version must be a positive integer.');
  }
  if (!Number.isInteger(manifest.min_runtime_version) || manifest.min_runtime_version < 1) {
    result.errors.push('min_runtime_version must be a positive integer.');
  }
  if (
    Number.isInteger(manifest.runtime_version) &&
    Number.isInteger(manifest.min_runtime_version) &&
    manifest.min_runtime_version > manifest.runtime_version
  ) {
    result.errors.push('min_runtime_version cannot exceed runtime_version.');
  }
  if (manifest.min_runtime_version > RUNTIME_VERSION) {
    result.errors.push(`Plugin requires runtime ${manifest.min_runtime_version}; platform provides ${RUNTIME_VERSION}.`);
  }
  if (!Number.isInteger(manifest.data_schema_version) || manifest.data_schema_version < 1) {
    result.errors.push('data_schema_version must be a positive integer.');
  }

  const requiredCapabilities = normalizeStringArray(
    manifest.required_capabilities,
    'required_capabilities',
    result
  );
  const optionalCapabilities = normalizeStringArray(
    manifest.optional_capabilities,
    'optional_capabilities',
    result
  );
  const duplicateCapabilities = requiredCapabilities.filter((name) => optionalCapabilities.includes(name));
  if (duplicateCapabilities.length) {
    result.errors.push(`Capabilities cannot be both required and optional: ${[...new Set(duplicateCapabilities)].join(', ')}`);
  }
  requiredCapabilities.concat(optionalCapabilities).forEach((name) => {
    if (!manifestSchema.capabilities.includes(name)) result.errors.push(`Unknown capability: ${name}`);
  });

  const entrypoint = validateAssetPath(manifest.entrypoint, 'entrypoint', result);
  const icon = validateAssetPath(manifest.icon, 'icon', result);
  const migrationEntrypoint = manifest.migration_entrypoint === undefined
    ? ''
    : validateAssetPath(manifest.migration_entrypoint, 'migration_entrypoint', result);
  if (icon && !SUPPORTED_ICON_EXTENSIONS.has(path.extname(icon).toLowerCase())) {
    result.errors.push('icon must use SVG, PNG, WebP, JPG, or JPEG format.');
  }
  const dist = path.join(root, 'dist');
  if (!fs.existsSync(dist) || !fs.statSync(dist).isDirectory()) {
    result.errors.push('Missing dist/ directory.');
  } else {
    if (entrypoint && !fs.existsSync(path.join(dist, entrypoint))) result.errors.push(`entrypoint does not exist in dist/: ${entrypoint}`);
    if (migrationEntrypoint && !fs.existsSync(path.join(dist, migrationEntrypoint))) {
      result.errors.push(`migration_entrypoint does not exist in dist/: ${migrationEntrypoint}`);
    }
    if (icon) {
      const iconFile = path.join(dist, icon);
      if (!fs.existsSync(iconFile) || !fs.statSync(iconFile).isFile()) {
        result.errors.push(`icon does not exist in dist/: ${icon}`);
      } else {
        const iconBytes = fs.statSync(iconFile).size;
        if (iconBytes < 1 || iconBytes > MAX_ICON_BYTES) {
          result.errors.push('icon must be between 1 byte and 1 MiB.');
        }
      }
    }
  }

  const permissions = manifest.permissions || {};
  const required = normalizeStringArray(permissions.required, 'permissions.required', result);
  const optional = normalizeStringArray(permissions.optional, 'permissions.optional', result);
  const duplicated = required.filter((id) => optional.includes(id));
  if (duplicated.length) result.errors.push(`Permissions cannot be both required and optional: ${[...new Set(duplicated)].join(', ')}`);
  if (manifestSchema.permissions.length) {
    required.concat(optional).forEach((id) => {
      if (!manifestSchema.permissions.includes(id)) result.errors.push(`Unknown permission: ${id}`);
    });
  }

  validateMarketplaceAndConfiguration(manifest, required, result, manifestSchema);

  const originFields = ['connect_origins', 'media_origins', 'frame_origins', 'navigation_origins'];
  const originsByField = Object.fromEntries(originFields.map((field) => {
    const normalized = normalizeStringArray(manifest[field], field, result)
      .map((origin) => normalizeOrigin(origin, result, field, field !== 'navigation_origins'))
      .filter(Boolean);
    if (normalized.length !== new Set(normalized).size) result.errors.push(`${field} contains duplicates.`);
    return [field, normalized];
  }));
  if (!Array.isArray(manifest.bridge_origins) || manifest.bridge_origins.length !== 1 || manifest.bridge_origins[0] !== 'self') {
    result.errors.push('bridge_origins must be exactly ["self"].');
  }
  if (originsByField.frame_origins.length && !requiredCapabilities.includes('remote.frame.v1')) {
    result.errors.push('frame_origins requires remote.frame.v1 in required_capabilities.');
  }

  if (fs.existsSync(dist) && fs.statSync(dist).isDirectory()) {
    const allOrigins = new Set(originFields.flatMap((field) => originsByField[field]));
    scanExternalOrigins(dist, allOrigins, result);
    scanRemoteScripts(dist, result);
    scanIframeSandboxes(dist, originsByField.frame_origins, result);
  }

  return result;
}

function loadManifestSchema(repoRoot, result) {
  const docsSchema = path.join(repoRoot, 'docs', 'third-party-service-manifest.schema.json');
  const webSchema = path.join(repoRoot, 'web', 'assets', 'schemas', 'third-party-service-manifest.schema.json');
  if (!fs.existsSync(docsSchema) || !fs.existsSync(webSchema)) {
    result.errors.push('Missing docs/ or web/assets/schemas/ manifest schema.');
    return { permissions: [], capabilities: [], categories: [], configurationTypes: [] };
  }
  const docsText = fs.readFileSync(docsSchema, 'utf8');
  const webText = fs.readFileSync(webSchema, 'utf8');
  if (docsText !== webText) result.errors.push('docs/ and web/assets/schemas/ manifest schemas are not identical.');
  const schema = readJson(docsSchema, result);
  const schemaPermissions = schema?.$defs?.permission_array?.items?.enum;
  if (!Array.isArray(schemaPermissions)) {
    result.errors.push('Manifest schema is missing permission enum.');
    return { permissions: [], capabilities: [], categories: [], configurationTypes: [] };
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

  const categories = schema?.$defs?.marketplace?.properties?.category?.enum;
  const configurationTypes = schema?.$defs?.configuration?.properties?.type?.enum;
  const capabilities = schema?.$defs?.capability_array?.items?.enum;
  if (!Array.isArray(categories) || !Array.isArray(configurationTypes) || !Array.isArray(capabilities)) {
    result.errors.push('Manifest schema is missing capability, marketplace category, or configuration type enums.');
  }
  return {
    permissions,
    capabilities: Array.isArray(capabilities) ? capabilities : [],
    categories: Array.isArray(categories) ? categories : [],
    configurationTypes: Array.isArray(configurationTypes) ? configurationTypes : []
  };
}

function validateMarketplaceAndConfiguration(manifest, requiredPermissions, result, manifestSchema) {
  const configuration = Array.isArray(manifest.configuration) ? manifest.configuration : [];
  if (manifest.schema_version !== 3) return;
  if (!/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/.test(stringField(manifest, 'version').trim())) {
    result.errors.push('schema_version 3 version must use semantic versioning.');
  }
  const marketplace = manifest.marketplace;
  if (!marketplace || typeof marketplace !== 'object' || Array.isArray(marketplace)) {
    result.errors.push('schema_version 3 requires marketplace metadata.');
  } else {
    const category = stringField(marketplace, 'category').trim().toLowerCase();
    if (!manifestSchema.categories.includes(category)) result.errors.push(`Unknown marketplace category: ${category || '(blank)'}`);
    const tags = normalizeStringArray(marketplace.tags, 'marketplace.tags', result);
    if (tags.length > 5) result.errors.push('marketplace.tags cannot contain more than 5 entries.');
    if (tags.some((tag) => tag.length > 20)) result.errors.push('marketplace tags must be 1-20 characters.');
    if (tags.map((tag) => tag.toLowerCase()).length !== new Set(tags.map((tag) => tag.toLowerCase())).size) {
      result.errors.push('marketplace.tags contains duplicates.');
    }
    if (marketplace.license !== undefined) {
      const license = stringField(marketplace, 'license').trim();
      if (!license || license.length > 80) {
        result.errors.push('marketplace.license must be 1-80 characters when provided.');
      }
    }
  }
  if (!Array.isArray(manifest.configuration)) {
    result.errors.push('schema_version 3 configuration must be an array.');
    return;
  }
  if (configuration.length > 32) result.errors.push('configuration cannot contain more than 32 entries.');
  if (configuration.length && !requiredPermissions.includes('app.configuration.read')) {
    result.errors.push('configuration requires app.configuration.read in permissions.required.');
  }
  const keys = [];
  configuration.forEach((definition, index) => {
    const prefix = `configuration[${index}]`;
    if (!definition || typeof definition !== 'object' || Array.isArray(definition)) {
      result.errors.push(`${prefix} must be an object.`);
      return;
    }
    const key = stringField(definition, 'key').trim();
    keys.push(key);
    if (!/^[A-Z][A-Z0-9_]{0,63}$/.test(key)) result.errors.push(`${prefix}.key is invalid.`);
    const label = stringField(definition, 'label').trim();
    if (!label || label.length > 80) result.errors.push(`${prefix}.label must be 1-80 characters.`);
    const description = stringField(definition, 'description').trim();
    if (description.length > 240) result.errors.push(`${prefix}.description cannot exceed 240 characters.`);
    const type = stringField(definition, 'type').trim().toLowerCase();
    if (!manifestSchema.configurationTypes.includes(type)) result.errors.push(`${prefix}.type is unknown.`);
    const options = definition.options === undefined ? [] : normalizeStringArray(definition.options, `${prefix}.options`, result);
    if (type === 'select') {
      if (!options.length || options.length > 20 || options.length !== new Set(options).size) {
        result.errors.push(`${prefix}.options must contain 1-20 unique values for select.`);
      }
    } else if (options.length) {
      result.errors.push(`${prefix}.options is only valid for select.`);
    }
    if (type === 'secret' && definition.default !== undefined) result.errors.push(`${prefix}.default is forbidden for secret.`);
    validateConfigurationDefault(definition.default, type, options, prefix, result);
    if (typeof definition.required !== 'boolean') result.errors.push(`${prefix}.required must be a boolean.`);
  });
  const duplicates = keys.filter((key, index) => key && keys.indexOf(key) !== index);
  if (duplicates.length) result.errors.push(`configuration contains duplicate keys: ${[...new Set(duplicates)].join(', ')}`);
}

function validateConfigurationDefault(value, type, options, prefix, result) {
  if (value === undefined) return;
  if (typeof value !== 'string') {
    result.errors.push(`${prefix}.default must be a string.`);
    return;
  }
  const normalized = value.trim();
  if (type === 'number' && !Number.isFinite(Number(normalized))) result.errors.push(`${prefix}.default must be a number.`);
  if (type === 'boolean' && !['true', 'false'].includes(normalized)) result.errors.push(`${prefix}.default must be true or false.`);
  if (type === 'select' && !options.includes(normalized)) result.errors.push(`${prefix}.default must be one of its options.`);
  if (type === 'url') {
    try {
      const url = new URL(normalized);
      if (!['http:', 'https:'].includes(url.protocol) || !url.hostname) throw new Error('invalid');
    } catch {
      result.errors.push(`${prefix}.default must be an HTTP/HTTPS URL.`);
    }
  }
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
  const normalized = [];
  value.forEach((item, index) => {
    if (typeof item !== 'string') {
      result.errors.push(`${field}[${index}] must be a string.`);
      return;
    }
    const text = item.trim();
    if (!text) {
      result.errors.push(`${field}[${index}] must be a non-empty string.`);
      return;
    }
    normalized.push(text);
  });
  if (normalized.length !== new Set(normalized).size) {
    result.errors.push(`${field} contains duplicates.`);
  }
  return normalized;
}

function validateObjectShape(value, field, allowed, required, result) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    result.errors.push(`${field} must be an object.`);
    return;
  }
  const keys = Object.keys(value);
  keys.filter((key) => !allowed.has(key)).forEach((key) => {
    result.errors.push(`${field} contains unknown field: ${key}`);
  });
  [...required].filter((key) => !Object.prototype.hasOwnProperty.call(value, key)).forEach((key) => {
    result.errors.push(`${field} is missing required field: ${key}`);
  });
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

function normalizeOrigin(value, result, field = 'origin', blockCampusHosts = true, allowDevelopmentOrigins = false) {
  try {
    const url = new URL(value);
    const host = url.hostname.toLowerCase();
    const localDevelopmentOrigin =
      allowDevelopmentOrigins &&
      url.protocol === 'http:' &&
      ['localhost', '127.0.0.1', '[::1]', '::1'].includes(host);
    if (
      (url.protocol !== 'https:' && !localDevelopmentOrigin) ||
      url.username ||
      url.password ||
      url.search ||
      url.hash ||
      (url.pathname && url.pathname !== '/')
    ) {
      result.errors.push(`${field} entry must be an HTTPS origin without path/query/userinfo: ${value}`);
      return null;
    }
    if (!localDevelopmentOrigin && isPrivateOrLocalHost(host)) {
      result.errors.push(`${field} cannot include private, loopback, link-local, or local hosts: ${host}`);
      return null;
    }
    if (blockCampusHosts && isCampusHost(host)) {
      result.errors.push(`${field} cannot include campus service hosts; use campus.request: ${host}`);
      return null;
    }
    return `${url.protocol}//${url.host.toLowerCase()}`;
  } catch {
    result.errors.push(`Invalid ${field} entry: ${value}`);
    return null;
  }
}

function isCampusHost(host) {
  return CAMPUS_HOSTS.has(host) || host.endsWith('.bjtu.edu.cn');
}

function isPrivateOrLocalHost(host) {
  const normalized = host.replace(/^\[|\]$/g, '').toLowerCase();
  if (
    normalized === 'localhost' ||
    normalized.endsWith('.localhost') ||
    normalized.endsWith('.local') ||
    normalized.endsWith('.internal') ||
    normalized === '::' ||
    normalized === '::1'
  ) return true;
  if (normalized.includes(':')) {
    const firstHextet = normalized.startsWith('::')
      ? null
      : Number.parseInt(normalized.split(':', 1)[0], 16);
    if (
      Number.isInteger(firstHextet) &&
      (
        (firstHextet & 0xfe00) === 0xfc00 ||
        (firstHextet & 0xffc0) === 0xfe80 ||
        (firstHextet & 0xff00) === 0xff00
      )
    ) return true;
  }
  const parts = normalized.split('.').map(Number);
  if (parts.length !== 4 || parts.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) return false;
  return parts[0] === 10 ||
    parts[0] === 127 ||
    (parts[0] === 169 && parts[1] === 254) ||
    (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) ||
    (parts[0] === 192 && parts[1] === 168) ||
    parts[0] >= 224;
}

function scanRemoteScripts(dist, result) {
  walk(dist).filter((file) => ['.html', '.htm'].includes(path.extname(file).toLowerCase())).forEach((file) => {
    const content = fs.readFileSync(file, 'utf8');
    if (/<script\b[^>]*\bsrc\s*=\s*["']https?:\/\//i.test(content)) {
      result.errors.push(`${path.relative(dist, file)} loads remote executable JavaScript; v3 public artifacts must bundle scripts locally.`);
    }
  });
}

function scanIframeSandboxes(dist, frameOrigins, result) {
  const declaredFrameOrigins = new Set(frameOrigins);
  walk(dist).filter((file) => ['.html', '.htm'].includes(path.extname(file).toLowerCase())).forEach((file) => {
    const content = fs.readFileSync(file, 'utf8');
    for (const match of content.matchAll(/<iframe\b[^>]*>/gi)) {
      const tag = match[0];
      const src = /\bsrc\s*=\s*["']([^"']+)["']/i.exec(tag)?.[1] || '';
      const remoteOrigin = /^https?:\/\//i.test(src) ? normalizeOriginFromUrl(src) : null;
      if (remoteOrigin && !declaredFrameOrigins.has(remoteOrigin)) {
        result.errors.push(`${path.relative(dist, file)} embeds undeclared iframe origin ${remoteOrigin}.`);
      }
      if (!remoteOrigin) continue;
      const hasSandbox = /\bsandbox(?:\s|=|>)/i.test(tag);
      const sandboxValue = /\bsandbox\s*=\s*["']([^"']*)["']/i.exec(tag)?.[1] || '';
      if (!hasSandbox) {
        result.errors.push(`${path.relative(dist, file)} remote iframe must declare a sandbox attribute.`);
        continue;
      }
      const tokens = new Set(sandboxValue.toLowerCase().split(/\s+/).filter(Boolean));
      const unsafeTokens = [
        'allow-downloads',
        'allow-modals',
        'allow-popups',
        'allow-popups-to-escape-sandbox',
        'allow-presentation',
        'allow-top-navigation',
        'allow-top-navigation-by-user-activation'
      ].filter((token) => tokens.has(token));
      if (unsafeTokens.length) {
        result.errors.push(
          `${path.relative(dist, file)} remote iframe sandbox grants forbidden tokens: ${unsafeTokens.join(', ')}.`
        );
      }
      const missingTokens = ['allow-scripts', 'allow-forms', 'allow-same-origin']
        .filter((token) => !tokens.has(token));
      if (missingTokens.length) {
        result.errors.push(
          `${path.relative(dist, file)} remote iframe sandbox must include ${missingTokens.join(', ')}.`
        );
      }
    }
  });
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

if (require.main === module) main();

module.exports = {
  lintPlugin,
  loadManifestSchema,
  normalizeOrigin,
  walk
};
