import assert from 'node:assert/strict';
import { createWriteStream, promises as fs } from 'node:fs';
import { createRequire } from 'node:module';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import yazl from 'yazl';
import { computeDistDigest, extractZipSecure, validateAndBuildPackage } from '../../src/validator.js';

interface LintResult { errors: string[]; warnings: string[] }
interface LintSchema { permissions: string[]; categories: string[]; configurationTypes: string[] }
interface LintModule {
  lintPlugin(root: string, result: LintResult, schema: LintSchema): LintResult;
  loadManifestSchema(repositoryRoot: string, result: LintResult): LintSchema;
}

test('dist digest is stable and sensitive to content and path changes', async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'bjtu-plugin-digest-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  await fs.mkdir(path.join(root, 'assets'), { recursive: true });
  await fs.writeFile(path.join(root, 'index.html'), '<h1>hello</h1>');
  await fs.writeFile(path.join(root, 'assets', 'app.js'), 'console.log(1)');
  const first = await computeDistDigest(root);
  const second = await computeDistDigest(root);
  assert.deepEqual(second, first);
  assert.equal(first.fileCount, 2);
  await fs.writeFile(path.join(root, 'assets', 'app.js'), 'console.log(2)');
  assert.notEqual((await computeDistDigest(root)).sha256, first.sha256);
});

test('builds a canonical marketplace artifact containing only manifest and dist', async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'bjtu-plugin-package-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const sourceZip = path.join(root, 'source.zip');
  const zip = new yazl.ZipFile();
  const manifest = {
    schema_version: 2,
    id: 'bjtu.demo',
    name: 'Demo',
    description: 'Demo plugin',
    version: '1.0.0',
    entrypoint: 'index.html',
    icon: 'icon.svg',
    author: 'Alice',
    permissions: { required: [], optional: [] },
    allowed_origins: [],
    marketplace: { category: 'other', tags: [], license: 'MIT' },
    configuration: []
  };
  zip.addBuffer(Buffer.from(JSON.stringify(manifest)), 'repository/bjtu-service.json');
  zip.addBuffer(Buffer.from('<html></html>'), 'repository/dist/index.html');
  zip.addBuffer(Buffer.from('<svg></svg>'), 'repository/dist/icon.svg');
  const output = createWriteStream(sourceZip);
  zip.outputStream.pipe(output);
  zip.end();
  await new Promise<void>((resolve, reject) => {
    output.on('close', resolve);
    output.on('error', reject);
  });

  const artifact = path.join(root, 'artifact.zip');
  const result = await validateAndBuildPackage({
    sourceZip,
    workRoot: path.join(root, 'work'),
    repositoryRoot: path.resolve('..'),
    artifactPath: artifact,
    iconPath: path.join(root, 'icon.svg')
  });
  assert.match(result.archiveSha256, /^[a-f0-9]{64}$/);
  assert.equal(result.packageFileCount, 2);

  const unpacked = path.join(root, 'unpacked');
  await extractZipSecure(artifact, unpacked);
  const files = (await fs.readdir(unpacked, { recursive: true, withFileTypes: true }))
    .filter((entry) => entry.isFile())
    .map((entry) => path.relative(unpacked, path.join(entry.parentPath, entry.name)).split(path.sep).join('/'))
    .sort();
  assert.deepEqual(files, ['bjtu-service.json', 'dist/icon.svg', 'dist/index.html']);
});

test('rejects archives whose directory entries exceed the global entry limit', async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'bjtu-plugin-entry-limit-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const sourceZip = path.join(root, 'directories.zip');
  const zip = new yazl.ZipFile();
  for (let index = 0; index <= 1000; index += 1) zip.addEmptyDirectory(`directory-${index}`);
  const output = createWriteStream(sourceZip);
  zip.outputStream.pipe(output);
  zip.end();
  await new Promise<void>((resolve, reject) => {
    output.on('close', resolve);
    output.on('error', reject);
  });

  await assert.rejects(
    extractZipSecure(sourceZip, path.join(root, 'extracted')),
    /条目数量超过 1000/
  );
});

test('lint rejects non-string values instead of publishing the original malformed arrays', async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'bjtu-plugin-lint-types-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  await fs.mkdir(path.join(root, 'dist'));
  await fs.writeFile(path.join(root, 'dist', 'index.html'), '<html></html>');
  await fs.writeFile(path.join(root, 'dist', 'icon.svg'), '<svg></svg>');
  await fs.writeFile(path.join(root, 'bjtu-service.json'), JSON.stringify({
    schema_version: 2,
    id: 'bjtu.demo',
    name: 'Demo',
    description: 'Demo plugin',
    version: '1.0.0',
    entrypoint: 'index.html',
    icon: 'icon.svg',
    author: 'Alice',
    permissions: { required: [42], optional: [] },
    allowed_origins: [false],
    marketplace: { category: 'other', tags: [{}] },
    configuration: []
  }));
  const lintResult: LintResult = { errors: [], warnings: [] };
  const require = createRequire(import.meta.url);
  const lint = require(path.resolve('..', 'tools', 'third-party-service-lint.cjs')) as LintModule;
  const schema = lint.loadManifestSchema(path.resolve('..'), lintResult);

  lint.lintPlugin(root, lintResult, schema);

  assert.ok(lintResult.errors.some((error) => error.includes('permissions.required[0] must be a string')));
  assert.ok(lintResult.errors.some((error) => error.includes('allowed_origins[0] must be a string')));
  assert.ok(lintResult.errors.some((error) => error.includes('marketplace.tags[0] must be a string')));
});
