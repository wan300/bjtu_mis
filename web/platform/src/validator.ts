import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { promises as fs } from 'node:fs';
import fsSync from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import yauzl from 'yauzl';
import yazl from 'yazl';
import { PLUGIN_PACKAGE_LIMITS } from './generated/plugin-contract.js';

const MAX_ARCHIVE_BYTES = PLUGIN_PACKAGE_LIMITS.archiveBytes;
const MAX_EXTRACTED_BYTES = PLUGIN_PACKAGE_LIMITS.extractedBytes;
const MAX_FILES = PLUGIN_PACKAGE_LIMITS.files;

interface LintResult { errors: string[]; warnings: string[] }
interface LintSchema {
  permissions: string[];
  capabilities: string[];
  categories: string[];
  configurationTypes: string[];
  contractProfile: string;
  protocolVersion: number;
  runtimeFloor: number;
  packageLimits: {
    archiveBytes: number;
    extractedBytes: number;
    files: number;
    iconBytes: number;
  };
  contracts: Array<{ id: string; runtimeFloor: number }>;
}
interface LintModule {
  lintPlugin(
    root: string,
    result: LintResult,
    schema: LintSchema,
    options?: { requireMarketplace?: boolean }
  ): LintResult;
  loadManifestSchema(repositoryRoot: string, result: LintResult): LintSchema;
}

export interface ValidatedPackage {
  manifest: Record<string, unknown>;
  marketplace: Record<string, unknown>;
  contractProfile: string;
  runtimeFloor: number;
  capabilities: {
    required: string[];
    optional: string[];
  };
  warnings: string[];
  packageDigestSha256: string;
  packageFileCount: number;
  packageBytes: number;
  archiveSha256: string;
  archiveBytes: number;
  artifactPath: string;
  iconPath: string;
}

function openZip(file: string): Promise<yauzl.ZipFile> {
  return new Promise((resolve, reject) => yauzl.open(file, { lazyEntries: true, decodeStrings: true, validateEntrySizes: true }, (error, zip) => {
    if (error || !zip) reject(error ?? new Error('无法打开插件压缩包'));
    else resolve(zip);
  }));
}

function openEntry(zip: yauzl.ZipFile, entry: yauzl.Entry): Promise<NodeJS.ReadableStream> {
  return new Promise((resolve, reject) => zip.openReadStream(entry, (error, stream) => {
    if (error || !stream) reject(error ?? new Error('无法读取插件压缩包条目'));
    else resolve(stream);
  }));
}

export async function extractZipSecure(zipPath: string, destination: string): Promise<void> {
  const archive = await fs.stat(zipPath);
  if (archive.size > MAX_ARCHIVE_BYTES) throw new Error('插件压缩包超过 25 MiB 限制');
  const zip = await openZip(zipPath);
  let entries = 0;
  let extractedBytes = 0;
  await fs.mkdir(destination, { recursive: true });
  await new Promise<void>((resolve, reject) => {
    const fail = (error: unknown) => {
      zip.close();
      reject(error);
    };
    zip.on('error', fail);
    zip.on('end', resolve);
    zip.on('entry', async (entry) => {
      try {
        const rawName = entry.fileName;
        entries += 1;
        if (entries > MAX_FILES) throw new Error(`插件压缩包条目数量超过 ${MAX_FILES} 限制`);
        if (!rawName || rawName.includes('\\') || rawName.includes(':') || rawName.startsWith('/') || rawName.split('/').includes('..')) {
          throw new Error(`压缩包包含越界路径：${rawName}`);
        }
        const mode = (entry.externalFileAttributes >>> 16) & 0xffff;
        const fileType = mode & 0o170000;
        if (fileType === 0o120000 || (fileType !== 0 && fileType !== 0o100000 && fileType !== 0o040000)) {
          throw new Error(`压缩包包含不支持的条目类型：${rawName}`);
        }
        const target = path.resolve(destination, ...rawName.split('/'));
        const rootPrefix = `${path.resolve(destination)}${path.sep}`;
        if (target !== path.resolve(destination) && !target.startsWith(rootPrefix)) throw new Error(`压缩包包含越界路径：${rawName}`);
        if (rawName.endsWith('/')) {
          await fs.mkdir(target, { recursive: true });
          zip.readEntry();
          return;
        }
        extractedBytes += entry.uncompressedSize;
        if (extractedBytes > MAX_EXTRACTED_BYTES) throw new Error('插件解压后超过 50 MiB 限制');
        await fs.mkdir(path.dirname(target), { recursive: true });
        const stream = await openEntry(zip, entry);
        const output = fsSync.createWriteStream(target, { flags: 'wx', mode: 0o600 });
        stream.pipe(output);
        await new Promise<void>((done, streamReject) => {
          stream.on('error', streamReject);
          output.on('error', streamReject);
          output.on('close', done);
        });
        zip.readEntry();
      } catch (error) {
        fail(error);
      }
    });
    zip.readEntry();
  });
}

async function locatePackageRoot(extractedRoot: string): Promise<string> {
  if (isFile(path.join(extractedRoot, 'bjtu-plugin.json'))) return extractedRoot;
  const entries = await fs.readdir(extractedRoot, { withFileTypes: true });
  const candidates = entries.filter((entry) => entry.isDirectory() && isFile(path.join(extractedRoot, entry.name, 'bjtu-plugin.json')));
  if (candidates.length !== 1) {
    throw new Error('插件仓库根目录缺少 bjtu-plugin.json；P0-A bjtu-service.json 不再允许发布');
  }
  return path.join(extractedRoot, candidates[0]!.name);
}

function isFile(file: string): boolean {
  try {
    return fsSync.statSync(file).isFile();
  } catch {
    return false;
  }
}

async function filesUnder(root: string): Promise<Array<{ absolute: string; relative: string; size: number }>> {
  const out: Array<{ absolute: string; relative: string; size: number }> = [];
  async function visit(directory: string): Promise<void> {
    const entries = await fs.readdir(directory, { withFileTypes: true });
    for (const entry of entries) {
      const absolute = path.join(directory, entry.name);
      if (entry.isSymbolicLink()) throw new Error(`插件包含符号链接：${path.relative(root, absolute)}`);
      if (entry.isDirectory()) await visit(absolute);
      else if (entry.isFile()) {
        const stat = await fs.stat(absolute);
        out.push({ absolute, relative: path.relative(root, absolute).split(path.sep).join('/'), size: stat.size });
      }
    }
  }
  await visit(root);
  return out.sort((a, b) => a.relative.localeCompare(b.relative));
}

export async function computeDistDigest(distRoot: string): Promise<{ sha256: string; fileCount: number; totalBytes: number }> {
  const files = await filesUnder(distRoot);
  const digest = createHash('sha256');
  let totalBytes = 0;
  for (const file of files) {
    totalBytes += file.size;
    digest.update(`file\0${file.relative}\0${file.size}\0`, 'utf8');
    for await (const chunk of createReadStream(file.absolute)) digest.update(chunk as Buffer);
    digest.update(Buffer.from([0]));
  }
  return { sha256: digest.digest('hex'), fileCount: files.length, totalBytes };
}

async function fileSha256(file: string): Promise<string> {
  const digest = createHash('sha256');
  for await (const chunk of createReadStream(file)) digest.update(chunk as Buffer);
  return digest.digest('hex');
}

async function createCanonicalArtifact(
  packageRoot: string,
  manifest: Record<string, unknown>,
  marketplace: Record<string, unknown>,
  artifactPath: string
): Promise<void> {
  const zip = new yazl.ZipFile();
  const epoch = new Date('1980-01-01T00:00:00Z');
  zip.addBuffer(Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`, 'utf8'), 'bjtu-plugin.json', { mtime: epoch, mode: 0o100644 });
  zip.addBuffer(Buffer.from(`${JSON.stringify(marketplace, null, 2)}\n`, 'utf8'), 'bjtu-marketplace.json', { mtime: epoch, mode: 0o100644 });
  const distRoot = path.join(packageRoot, 'dist');
  for (const file of await filesUnder(distRoot)) {
    zip.addFile(file.absolute, `dist/${file.relative}`, { mtime: epoch, mode: 0o100644 });
  }
  await fs.mkdir(path.dirname(artifactPath), { recursive: true });
  const output = fsSync.createWriteStream(artifactPath, { flags: 'wx', mode: 0o600 });
  zip.outputStream.pipe(output);
  zip.end({ forceZip64Format: false });
  await new Promise<void>((resolve, reject) => {
    zip.outputStream.on('error', reject);
    output.on('error', reject);
    output.on('close', resolve);
  });
}

export async function validateAndBuildPackage(options: {
  sourceZip: string;
  workRoot: string;
  repositoryRoot: string;
  artifactPath: string;
  iconPath: string;
}): Promise<ValidatedPackage> {
  const extracted = path.join(options.workRoot, 'extracted');
  await extractZipSecure(options.sourceZip, extracted);
  const packageRoot = await locatePackageRoot(extracted);
  const lintResult: LintResult = { errors: [], warnings: [] };
  const require = createRequire(import.meta.url);
  const lint = require(path.join(options.repositoryRoot, 'tools', 'third-party-service-lint.cjs')) as LintModule;
  const lintSchema = lint.loadManifestSchema(options.repositoryRoot, lintResult);
  lint.lintPlugin(packageRoot, lintResult, lintSchema, { requireMarketplace: true });
  if (lintResult.errors.length) throw new Error(lintResult.errors.join('\n'));
  const manifest = JSON.parse(
    await fs.readFile(path.join(packageRoot, 'bjtu-plugin.json'), 'utf8')
  ) as Record<string, unknown>;
  const marketplace = JSON.parse(
    await fs.readFile(path.join(packageRoot, 'bjtu-marketplace.json'), 'utf8')
  ) as Record<string, unknown>;
  if (manifest.schema_version !== 3) throw new Error('插件大厅投稿必须使用 schema_version 3');
  const declaration = manifest.capabilities as {
    required?: unknown;
    optional?: unknown;
  };
  const capabilities = {
    required: Array.isArray(declaration?.required)
      ? declaration.required.filter((value): value is string => typeof value === 'string')
      : [],
    optional: Array.isArray(declaration?.optional)
      ? declaration.optional.filter((value): value is string => typeof value === 'string')
      : []
  };
  const runtimeFloor = Math.max(
    lintSchema.runtimeFloor,
    ...[...capabilities.required, ...capabilities.optional].map(
      (id) => lintSchema.contracts.find((contract) => contract.id === id)?.runtimeFloor ?? 0
    )
  );
  const distRoot = path.join(packageRoot, 'dist');
  const digest = await computeDistDigest(distRoot);
  await createCanonicalArtifact(packageRoot, manifest, marketplace, options.artifactPath);
  const artifactStat = await fs.stat(options.artifactPath);
  if (artifactStat.size > MAX_ARCHIVE_BYTES) throw new Error('规范化插件包超过 25 MiB 限制');
  const icon = String(manifest.icon ?? '');
  const iconSource = path.resolve(distRoot, ...icon.split('/'));
  if (!iconSource.startsWith(`${path.resolve(distRoot)}${path.sep}`)) throw new Error('插件图标路径越界');
  await fs.mkdir(path.dirname(options.iconPath), { recursive: true });
  await fs.copyFile(iconSource, options.iconPath, fsSync.constants.COPYFILE_EXCL);
  return {
    manifest,
    marketplace,
    contractProfile: lintSchema.contractProfile,
    runtimeFloor,
    capabilities,
    warnings: lintResult.warnings,
    packageDigestSha256: digest.sha256,
    packageFileCount: digest.fileCount,
    packageBytes: digest.totalBytes,
    archiveSha256: await fileSha256(options.artifactPath),
    archiveBytes: artifactStat.size,
    artifactPath: options.artifactPath,
    iconPath: options.iconPath
  };
}
