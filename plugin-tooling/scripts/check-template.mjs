import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';

const toolingRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const templateRoot = path.join(toolingRoot, 'packages', 'plugin-cli', 'template');

await build({
  root: templateRoot,
  configFile: path.join(templateRoot, 'vite.config.ts'),
  logLevel: 'silent',
  build: {
    write: false,
    emptyOutDir: false
  }
});

process.stdout.write('Vanilla TypeScript + Vite template check passed without writing dist.\n');
