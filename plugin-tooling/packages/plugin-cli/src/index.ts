import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  copyFile,
  mkdir,
  readFile,
  readdir,
  stat,
  writeFile
} from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { CAPABILITY_REGISTRY, PACKAGE_LIMITS } from '@bjtu-mis/plugin-sdk';
import { writeDeterministicZip, type ArchiveEntry } from './archive.js';
import { runBrowserSmokeTest } from './browser-smoke.js';
import {
  lintPlugin,
  type LintResult,
  type MarketplaceMetadata,
  type PluginManifest
} from './lint.js';

export { lintPlugin } from './lint.js';
export { inspectDeterministicZip, writeDeterministicZip } from './archive.js';
export { runBrowserSmokeTest } from './browser-smoke.js';

const packageRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const templateRoot = path.join(packageRoot, 'template');
const DEBUG_APPLICATION_ID = 'cn.edu.bjtu.mis.debug';
const DEBUG_RECEIVER =
  'cn.edu.bjtu.mis.data.thirdparty.PluginDevelopmentReceiver';
const DEBUG_ACTION = 'cn.edu.bjtu.mis.debug.PLUGIN_DEVELOPMENT';

interface PluginDevelopmentConfig {
  mock?: Record<string, unknown>;
  hmr: {
    host: string;
    port: number;
    adbReversePort: number;
  };
}

export interface CommandInvocation {
  command: string;
  args: string[];
}

export async function runCli(args = process.argv.slice(2)): Promise<number> {
  const [command = 'help', ...rest] = args;
  try {
    switch (command) {
      case 'create':
        await createCommand(rest);
        return 0;
      case 'dev':
        return devCommand(rest);
      case 'lint':
        return lintCommand(rest);
      case 'test':
        return testCommand(rest);
      case 'pack':
        return packCommand(rest);
      case 'doctor':
        return doctorCommand(rest);
      case 'inspect':
        return inspectCommand(rest);
      case 'migrate':
        return migrateCommand(rest);
      case 'help':
      case '--help':
      case '-h':
        printHelp();
        return 0;
      default:
        process.stderr.write(`Unknown command: ${command}\n`);
        printHelp();
        return 2;
    }
  } catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
    return 1;
  }
}

async function createCommand(args: string[]): Promise<void> {
  const target = path.resolve(args.find((arg) => !arg.startsWith('-')) ?? 'bjtu-plugin');
  const id = option(args, '--id') ?? `io.example.${path.basename(target).toLowerCase().replace(/[^a-z0-9]+/g, '-')}`;
  if (!/^[a-z][a-z0-9_.-]{2,63}$/.test(id)) throw new Error(`Invalid plugin id: ${id}`);
  const existing = await stat(target).catch(() => null);
  if (existing && (await readdir(target)).length > 0) {
    throw new Error(`Target directory is not empty: ${target}`);
  }
  await copyTree(templateRoot, target);
  await replaceInFile(path.join(target, 'bjtu-plugin.json'), 'io.example.demo', id);
  await replaceInFile(path.join(target, 'package.json'), 'bjtu-plugin-demo', path.basename(target));
  process.stdout.write(`Created ${id} in ${target}\nNext: npm install && npm run dev\n`);
}

async function devCommand(args: string[]): Promise<number> {
  const root = path.resolve(directoryArgument(args, new Set(['--adb'])) ?? '.');
  const result = await lintPlugin(root, { requireDist: false });
  printLint(result);
  if (!result.ok) return 1;
  const development = await readDevelopmentConfig(root);
  const useAndroid = args.includes('--android');
  const disableAndroid = args.includes('--disable-android');
  if (useAndroid && disableAndroid) {
    throw new Error('--android and --disable-android cannot be used together.');
  }
  const adb = option(args, '--adb') ?? process.env.ADB ?? 'adb';
  if (disableAndroid) {
    return disableAndroidDevelopment(
      root,
      adb,
      result.manifest!.id,
      development.hmr.adbReversePort
    );
  }
  if (useAndroid) {
    const enabled = androidDevelopmentInvocations(
      adb,
      result.manifest!.id,
      development.hmr.port,
      development.hmr.adbReversePort,
      true
    );
    for (const [index, invocation] of enabled.entries()) {
      const code = await spawnAndWait(invocation.command, invocation.args, root);
      if (code !== 0) {
        if (index > 0) {
          await disableAndroidDevelopment(
            root,
            adb,
            result.manifest!.id,
            development.hmr.adbReversePort
          );
        } else {
          await spawnAndWait(
            adb,
            ['reverse', '--remove', `tcp:${development.hmr.adbReversePort}`],
            root
          );
        }
        return code;
      }
    }
  }
  const npm = npmInvocation(['run', 'dev']);
  try {
    return await spawnAndWait(npm.command, npm.args, root, {
      BJTU_ANDROID_HMR: useAndroid ? '1' : '0',
      BJTU_VITE_PORT: String(development.hmr.port)
    });
  } finally {
    if (useAndroid) {
      await disableAndroidDevelopment(
        root,
        adb,
        result.manifest!.id,
        development.hmr.adbReversePort
      );
    }
  }
}

async function lintCommand(args: string[]): Promise<number> {
  const root = path.resolve(args.find((arg) => !arg.startsWith('-')) ?? '.');
  const result = await lintPlugin(root, {
    requireMarketplace: args.includes('--marketplace'),
    requireDist: !args.includes('--source')
  });
  printLint(result);
  return result.ok ? 0 : 1;
}

async function testCommand(args: string[]): Promise<number> {
  const root = path.resolve(
    directoryArgument(args, new Set(['--browser'])) ?? '.'
  );
  const result = await lintPlugin(root, { requireDist: true });
  printLint(result);
  if (!result.ok) return 1;
  const smoke = await runBrowserSmokeTest(
    root,
    result.manifest!.entrypoint,
    option(args, '--browser')
  );
  process.stdout.write(
    `Plugin browser smoke passed with ${path.basename(smoke.browser)} (${smoke.requestCount} capability requests).\n`
  );
  return 0;
}

async function packCommand(args: string[]): Promise<number> {
  const root = path.resolve(args.find((arg) => !arg.startsWith('-')) ?? '.');
  const result = await lintPlugin(root, { requireDist: true });
  printLint(result);
  if (!result.ok) return 1;
  const output = path.resolve(
    option(args, '--out') ?? path.join(root, `${result.manifest!.id}-${result.manifest!.version}.zip`)
  );
  const entries = await packageEntries(root);
  if (entries.some((entry) => entry.name === 'bjtu-plugin.dev.json')) {
    throw new Error('bjtu-plugin.dev.json is forbidden in release packages.');
  }
  await writeDeterministicZip(output, entries, PACKAGE_LIMITS);
  const digest = createHash('sha256').update(await readFile(output)).digest('hex');
  process.stdout.write(`Packed ${output}\nsha256 ${digest}\n`);
  return 0;
}

async function doctorCommand(args: string[]): Promise<number> {
  const root = path.resolve(args.find((arg) => !arg.startsWith('-')) ?? '.');
  const result = await lintPlugin(root, { requireDist: false });
  const report = {
    node: process.version,
    supportedNode: Number(process.versions.node.split('.')[0]) >= 20 && Number(process.versions.node.split('.')[0]) <= 22,
    contractProfile: CAPABILITY_REGISTRY.contractProfile,
    protocolVersion: CAPABILITY_REGISTRY.protocolVersion,
    manifest: result.ok,
    runtimeFloor: result.runtimeFloor,
    devConfig: Boolean(await stat(path.join(root, 'bjtu-plugin.dev.json')).catch(() => null)),
    arrayBufferRequired:
      result.manifest?.capabilities.required.some((id) =>
        ['storage.blob@1', 'cache.resource@1'].includes(id)
      ) ?? false
  };
  process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
  printLint(result);
  return report.supportedNode && result.ok ? 0 : 1;
}

async function inspectCommand(args: string[]): Promise<number> {
  const root = path.resolve(args.find((arg) => !arg.startsWith('-')) ?? '.');
  const result = await lintPlugin(root, { requireDist: false });
  if (!result.ok || !result.manifest) {
    printLint(result);
    return 1;
  }
  const ids = [
    ...result.manifest.capabilities.required.map((id) => ({ id, required: true })),
    ...(result.manifest.capabilities.optional ?? []).map((id) => ({ id, required: false }))
  ];
  const capabilities = ids.map(({ id, required }) => {
    const contract = CAPABILITY_REGISTRY.capabilities.find((item) => item.id === id);
    return {
      id,
      required,
      stability: contract?.stability,
      permission: contract?.permission ?? null,
      confirmation: contract?.confirmation,
      idempotency: contract?.idempotency,
      timeoutMs: contract?.timeoutMs,
      methods: contract?.methods.map((method) => method.name) ?? []
    };
  });
  process.stdout.write(
    `${JSON.stringify(
      {
        contractProfile: CAPABILITY_REGISTRY.contractProfile,
        runtimeFloor: result.runtimeFloor,
        plugin: {
          id: result.manifest.id,
          version: result.manifest.version
        },
        capabilities
      },
      null,
      2
    )}\n`
  );
  return 0;
}

async function migrateCommand(args: string[]): Promise<number> {
  const root = path.resolve(args.find((arg) => !arg.startsWith('-')) ?? '.');
  const oldPath = path.join(root, 'bjtu-service.json');
  const targetPath = path.join(root, 'bjtu-plugin.json');
  if ((await stat(targetPath).catch(() => null)) && !args.includes('--force')) {
    throw new Error('bjtu-plugin.json already exists; pass --force to replace it.');
  }
  const old = JSON.parse(await readFile(oldPath, 'utf8')) as Record<string, unknown>;
  if (old.schema_version !== 3) {
    throw new Error('Only P0-A Manifest v3 can be migrated automatically; v1/v2 remain rescue-only.');
  }
  const migrated = migrateP0aManifest(old);
  await writeFile(targetPath, `${JSON.stringify(migrated.manifest, null, 2)}\n`, 'utf8');
  if (migrated.marketplace) {
    await writeFile(
      path.join(root, 'bjtu-marketplace.json'),
      `${JSON.stringify(migrated.marketplace, null, 2)}\n`,
      'utf8'
    );
  }
  process.stdout.write(
    `Migrated P0-A manifest. Review capabilities and origin declarations before publishing.\n`
  );
  return 0;
}

export function migrateP0aManifest(old: Record<string, unknown>): {
  manifest: PluginManifest;
  marketplace?: MarketplaceMetadata;
} {
  const permissions = isObject(old.permissions) ? old.permissions : {};
  const requiredPermissions = stringValues(permissions.required);
  const optionalPermissions = stringValues(permissions.optional);
  const oldRequiredCapabilities = stringValues(old.required_capabilities);
  const oldOptionalCapabilities = stringValues(old.optional_capabilities);
  const required = new Set<string>([
    'runtime.lifecycle@1',
    ...oldRequiredCapabilities.map(migrateCapability).filter(Boolean),
    ...requiredPermissions.map(permissionCapability).filter(Boolean)
  ]);
  const optional = new Set<string>([
    ...oldOptionalCapabilities.map(migrateCapability).filter(Boolean),
    ...optionalPermissions.map(permissionCapability).filter(Boolean)
  ]);
  for (const id of required) optional.delete(id);
  const origins = compactObject({
    connect: nonEmptyStringValues(old.connect_origins),
    media: nonEmptyStringValues(old.media_origins),
    frame: nonEmptyStringValues(old.frame_origins),
    navigation: nonEmptyStringValues(old.navigation_origins)
  });
  const usesData = [...required, ...optional].some((id) =>
    ['storage.kv@2', 'storage.blob@1'].includes(id)
  );
  const manifest = compactObject({
    schema_version: 3,
    id: String(old.id ?? ''),
    name: String(old.name ?? ''),
    version: String(old.version ?? ''),
    entrypoint: String(old.entrypoint ?? ''),
    icon: String(old.icon ?? ''),
    capabilities: compactObject({
      required: [...required],
      optional: optional.size ? [...optional] : undefined
    }),
    origins: Object.keys(origins).length ? origins : undefined,
    data_schema_version: usesData ? Number(old.data_schema_version ?? 1) : undefined,
    migration_entrypoint: usesData ? old.migration_entrypoint : undefined,
    configuration:
      Array.isArray(old.configuration) && old.configuration.length ? old.configuration : undefined
  }) as unknown as PluginManifest;
  const oldMarketplace = isObject(old.marketplace) ? old.marketplace : {};
  const description = typeof old.description === 'string' ? old.description : '';
  const author = typeof old.author === 'string' ? old.author : '';
  const marketplace =
    description && author
      ? ({
          description,
          author,
          category: String(oldMarketplace.category ?? 'other'),
          tags: stringValues(oldMarketplace.tags),
          ...(typeof oldMarketplace.license === 'string'
            ? { license: oldMarketplace.license }
            : {})
        } as MarketplaceMetadata)
      : undefined;
  return { manifest, marketplace };
}

async function packageEntries(root: string): Promise<ArchiveEntry[]> {
  const entries: ArchiveEntry[] = [
    {
      source: path.join(root, 'bjtu-plugin.json'),
      name: 'bjtu-plugin.json'
    }
  ];
  if (await stat(path.join(root, 'bjtu-marketplace.json')).catch(() => null)) {
    entries.push({
      source: path.join(root, 'bjtu-marketplace.json'),
      name: 'bjtu-marketplace.json'
    });
  }
  for (const file of await walk(path.join(root, 'dist'))) {
    entries.push({
      source: file,
      name: `dist/${path.relative(path.join(root, 'dist'), file).replaceAll('\\', '/')}`
    });
  }
  return entries;
}

function printLint(result: LintResult): void {
  for (const warning of result.warnings) {
    process.stderr.write(`warning [${warning.code}]${warning.file ? ` ${warning.file}` : ''}: ${warning.message}\n`);
  }
  for (const error of result.errors) {
    process.stderr.write(`error [${error.code}]${error.file ? ` ${error.file}` : ''}: ${error.message}\n`);
  }
  if (result.ok) {
    process.stdout.write(
      `Lint passed for ${result.manifest?.id ?? 'plugin'} (runtime floor ${result.runtimeFloor}).\n`
    );
  }
}

function printHelp(): void {
  process.stdout.write(`BJTU plugin CLI

Usage: bjtu <command> [directory] [options]

Commands:
  create   Scaffold a Vanilla TypeScript + Vite plugin
  dev      Start Mock Host, or debug-app HMR with --android [--adb <path>]
  lint     Validate manifest, origins, assets and package policy
  test     Run lint plus a real headless-browser protocol v2 smoke check
  pack     Build a deterministic ZIP without bjtu-plugin.dev.json
  doctor   Diagnose Node, contract, binary and development support
  inspect  Print derived capabilities, permissions and runtime floor
  migrate  Convert a P0-A bjtu-service.json into contract_v1 files
`);
}

function option(args: string[], name: string): string | undefined {
  const index = args.indexOf(name);
  return index >= 0 ? args[index + 1] : undefined;
}

function directoryArgument(
  args: string[],
  valueOptions: ReadonlySet<string>
): string | undefined {
  for (let index = 0; index < args.length; index += 1) {
    const value = args[index];
    if (valueOptions.has(value)) {
      index += 1;
      continue;
    }
    if (!value.startsWith('-')) return value;
  }
  return undefined;
}

function npmInvocation(args: string[]): { command: string; args: string[] } {
  const npmExecPath = process.env.npm_execpath;
  if (npmExecPath) {
    return {
      command: process.execPath,
      args: [npmExecPath, ...args]
    };
  }
  if (process.platform === 'win32') {
    return {
      command: process.env.ComSpec ?? 'C:\\Windows\\System32\\cmd.exe',
      args: ['/d', '/s', '/c', 'npm', ...args]
    };
  }
  return {
    command: 'npm',
    args
  };
}

async function spawnAndWait(
  command: string,
  args: string[],
  cwd: string,
  environment: Record<string, string> = {}
): Promise<number> {
  return await new Promise<number>((resolve, reject) => {
    const child = spawn(command, args, {
      cwd,
      env: { ...process.env, ...environment },
      stdio: 'inherit',
      windowsHide: true
    });
    child.once('error', reject);
    child.once('exit', (code) => resolve(code ?? 1));
  });
}

async function readDevelopmentConfig(root: string): Promise<PluginDevelopmentConfig> {
  const file = path.join(root, 'bjtu-plugin.dev.json');
  const fileInfo = await stat(file).catch(() => null);
  if (!fileInfo?.isFile()) {
    throw new Error(
      'bjtu-plugin.dev.json is required for the explicit debug Mock/HMR workflow.'
    );
  }
  const raw = JSON.parse(await readFile(file, 'utf8')) as unknown;
  if (!isObject(raw) || !isObject(raw.hmr)) {
    throw new Error('bjtu-plugin.dev.json must contain an hmr object.');
  }
  const host = typeof raw.hmr.host === 'string' ? raw.hmr.host : '127.0.0.1';
  if (!['127.0.0.1', 'localhost', '::1'].includes(host)) {
    throw new Error('Development HMR host must be loopback.');
  }
  const port = Number(raw.hmr.port ?? 5173);
  const adbReversePort = Number(raw.hmr.adb_reverse_port ?? port);
  for (const [name, value] of [
    ['hmr.port', port],
    ['hmr.adb_reverse_port', adbReversePort]
  ] as const) {
    if (!Number.isInteger(value) || value < 1024 || value > 65535) {
      throw new Error(`${name} must be an integer from 1024 through 65535.`);
    }
  }
  return {
    mock: isObject(raw.mock) ? raw.mock : undefined,
    hmr: { host, port, adbReversePort }
  };
}

export function androidDevelopmentInvocations(
  adb: string,
  pluginId: string,
  vitePort: number,
  adbReversePort: number,
  enabled: boolean
): CommandInvocation[] {
  const receiver = `${DEBUG_APPLICATION_ID}/${DEBUG_RECEIVER}`;
  if (!enabled) {
    return [
      {
        command: adb,
        args: [
          'shell',
          'am',
          'broadcast',
          '-n',
          receiver,
          '-a',
          DEBUG_ACTION,
          '--es',
          'pluginId',
          pluginId,
          '--ei',
          'port',
          String(adbReversePort),
          '--ez',
          'enabled',
          'false'
        ]
      },
      {
        command: adb,
        args: ['reverse', '--remove', `tcp:${adbReversePort}`]
      }
    ];
  }
  return [
    {
      command: adb,
      args: ['reverse', `tcp:${adbReversePort}`, `tcp:${vitePort}`]
    },
    {
      command: adb,
      args: [
        'shell',
        'am',
        'broadcast',
        '-n',
        receiver,
        '-a',
        DEBUG_ACTION,
        '--es',
        'pluginId',
        pluginId,
        '--ei',
        'port',
        String(adbReversePort),
        '--ez',
        'enabled',
        'true'
      ]
    }
  ];
}

async function disableAndroidDevelopment(
  root: string,
  adb: string,
  pluginId: string,
  adbReversePort: number
): Promise<number> {
  let exitCode = 0;
  for (const invocation of androidDevelopmentInvocations(
    adb,
    pluginId,
    adbReversePort,
    adbReversePort,
    false
  )) {
    const code = await spawnAndWait(invocation.command, invocation.args, root);
    if (code !== 0) exitCode = code;
  }
  return exitCode;
}

async function copyTree(source: string, target: string): Promise<void> {
  await mkdir(target, { recursive: true });
  for (const entry of await readdir(source, { withFileTypes: true })) {
    const from = path.join(source, entry.name);
    const to = path.join(target, entry.name);
    if (entry.isDirectory()) await copyTree(from, to);
    else if (entry.isFile()) await copyFile(from, to);
  }
}

async function replaceInFile(file: string, search: string, replacement: string): Promise<void> {
  const content = await readFile(file, 'utf8');
  await writeFile(file, content.replaceAll(search, replacement), 'utf8');
}

async function walk(root: string): Promise<string[]> {
  const output: string[] = [];
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const fullPath = path.join(root, entry.name);
    if (entry.isDirectory()) output.push(...(await walk(fullPath)));
    else if (entry.isFile()) output.push(fullPath);
  }
  return output;
}

function compactObject<T extends Record<string, unknown>>(value: T): T {
  return Object.fromEntries(
    Object.entries(value).filter(([, item]) => item !== undefined)
  ) as T;
}

function stringValues(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string')
    : [];
}

function nonEmptyStringValues(value: unknown): string[] | undefined {
  const values = stringValues(value);
  return values.length ? values : undefined;
}

function migrateCapability(value: string): string {
  return (
    {
      'runtime.lifecycle.v1': 'runtime.lifecycle@1',
      'storage.kv.v1': 'storage.kv@2',
      'campus.request.v1': 'campus.request@1',
      'remote.frame.v1': 'remote.frame@1'
    } as Record<string, string>
  )[value] ?? '';
}

function permissionCapability(value: string): string {
  const mapping: Record<string, string> = {
    'app.configuration.read': 'configuration.read@1',
    'identity.profile.read': 'identity.profile@1',
    'academic.timetable.read': 'academic.timetable@1',
    'academic.scores.read': 'academic.scores@1',
    'academic.history_scores.read': 'academic.scores@1',
    'academic.exams.read': 'academic.exams@1',
    'academic.calendar.read': 'academic.calendar@1',
    'academic.progress.read': 'academic.progress@1',
    'academic.homework.read': 'academic.homework@1',
    'academic.course_resources.read': 'academic.resources@1',
    'academic.user_courses.write': 'academic.userCourses.command@1',
    'academic.homework.submit': 'academic.homework.submit@1',
    'mail.folders.read': 'mail.read@1',
    'mail.messages.read': 'mail.read@1',
    'mail.message_detail.read': 'mail.read@1',
    'mail.send': 'mail.send@1'
  };
  return mapping[value] ?? '';
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
