import { promises as fs } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadConfig } from './config.js';
import { createDatabase } from './db.js';

export async function runMigrations(databaseUrl: string): Promise<void> {
  const db = createDatabase(databaseUrl);
  try {
    const compiledDirectory = path.join(path.dirname(fileURLToPath(import.meta.url)), 'migrations');
    const sourceDirectory = path.join(process.cwd(), 'src', 'migrations');
    const directory = await fs.stat(compiledDirectory).then(() => compiledDirectory).catch(() => sourceDirectory);
    const files = (await fs.readdir(directory)).filter((name) => name.endsWith('.sql')).sort();
    for (const file of files) {
      await db.query(await fs.readFile(path.join(directory, file), 'utf8'));
    }
  } finally {
    await db.end();
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  const config = loadConfig();
  await runMigrations(config.databaseUrl);
}
