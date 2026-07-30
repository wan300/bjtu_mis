import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  cp,
  mkdir,
  mkdtemp,
  readFile,
  rm,
  writeFile
} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { runCli } from '../packages/plugin-cli/dist/index.js';

const toolingRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const temporary = await mkdtemp(path.join(os.tmpdir(), 'bjtu-pack-check-'));

try {
  for (const workspace of ['plugin-sdk', 'plugin-cli', 'create-bjtu-plugin']) {
    const npmExecPath = process.env.npm_execpath;
    const result = spawnSync(
      npmExecPath ? process.execPath : process.platform === 'win32' ? process.env.ComSpec ?? 'cmd.exe' : 'npm',
      npmExecPath
        ? [npmExecPath, 'pack', '--json', '--pack-destination', temporary]
        : process.platform === 'win32'
          ? ['/d', '/s', '/c', 'npm', 'pack', '--json', '--pack-destination', temporary]
          : ['pack', '--json', '--pack-destination', temporary],
      {
        cwd: path.join(toolingRoot, 'packages', workspace),
        encoding: 'utf8',
        windowsHide: true
      }
    );
    if (result.status !== 0) {
      throw new Error(
        result.error?.message ||
          result.stderr ||
          result.stdout ||
          `npm pack failed for ${workspace} with status ${String(result.status)}`
      );
    }
    const report = JSON.parse(result.stdout);
    assert.equal(report.length, 1);
    assert.ok(report[0].files.some((entry) => entry.path.startsWith('dist/')));
  }

  const plugin = path.join(temporary, 'plugin');
  await cp(path.join(toolingRoot, 'packages', 'plugin-cli', 'template'), plugin, {
    recursive: true
  });
  await mkdir(path.join(plugin, 'dist'), { recursive: true });
  await writeFile(
    path.join(plugin, 'dist', 'index.html'),
    '<!doctype html><script type="module" src="./main.js"></script>',
    'utf8'
  );
  await writeFile(path.join(plugin, 'dist', 'main.js'), 'export {};\n', 'utf8');
  await cp(path.join(plugin, 'public', 'icon.svg'), path.join(plugin, 'dist', 'icon.svg'));

  const first = path.join(temporary, 'plugin-1.zip');
  const second = path.join(temporary, 'plugin-2.zip');
  assert.equal(await runCli(['pack', plugin, '--out', first]), 0);
  assert.equal(await runCli(['pack', plugin, '--out', second]), 0);
  const firstBytes = await readFile(first);
  const secondBytes = await readFile(second);
  assert.equal(
    createHash('sha256').update(firstBytes).digest('hex'),
    createHash('sha256').update(secondBytes).digest('hex')
  );
  assert.equal(firstBytes.includes(Buffer.from('bjtu-plugin.dev.json')), false);
  process.stdout.write('npm pack and deterministic plugin archive checks passed.\n');
} finally {
  await rm(temporary, { recursive: true, force: true });
}
