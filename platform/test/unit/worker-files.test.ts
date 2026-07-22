import assert from 'node:assert/strict';
import { promises as fs } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { publishStagedFile } from '../../src/worker.js';

test('publishes a staged file through a temporary file in the artifact directory', async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'bjtu-worker-file-test-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const source = path.join(root, 'work', 'artifact.zip');
  const target = path.join(root, 'artifacts', 'plugin', 'commit', 'plugin.zip');
  await fs.mkdir(path.dirname(source), { recursive: true });
  await fs.writeFile(source, 'verified artifact');

  await publishStagedFile(source, target);

  assert.equal(await fs.readFile(target, 'utf8'), 'verified artifact');
  const leftovers = (await fs.readdir(path.dirname(target))).filter((name) => name.endsWith('.tmp'));
  assert.deepEqual(leftovers, []);
});
