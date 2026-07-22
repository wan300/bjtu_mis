import path from 'node:path';

function required(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`Missing required environment variable: ${name}`);
  return value;
}

function integer(name: string, fallback: number): number {
  const value = Number.parseInt(process.env[name] ?? '', 10);
  return Number.isFinite(value) ? value : fallback;
}

export interface PlatformConfig {
  nodeEnv: string;
  host: string;
  port: number;
  publicBaseUrl: string;
  databaseUrl: string;
  githubClientId: string;
  githubClientSecret: string;
  tokenEncryptionKey: Buffer;
  sessionCookieName: string;
  adminGithubIds: Set<string>;
  reservedPluginIds: Set<string>;
  artifactRoot: string;
  repositoryRoot: string;
  pollIntervalMinutes: number;
}

export function loadConfig(options: { allowMissingOAuth?: boolean } = {}): PlatformConfig {
  const keyText = process.env.TOKEN_ENCRYPTION_KEY_BASE64?.trim();
  const key = keyText ? Buffer.from(keyText, 'base64') : Buffer.alloc(32);
  if (!options.allowMissingOAuth && (!keyText || key.length !== 32)) {
    throw new Error('TOKEN_ENCRYPTION_KEY_BASE64 must decode to exactly 32 bytes');
  }
  return {
    nodeEnv: process.env.NODE_ENV?.trim() || 'development',
    host: process.env.HOST?.trim() || '127.0.0.1',
    port: integer('PORT', 15020),
    publicBaseUrl: (process.env.PUBLIC_BASE_URL?.trim() || 'http://127.0.0.1:15020').replace(/\/$/, ''),
    databaseUrl: process.env.DATABASE_URL?.trim() || 'postgres://bjtu_plugins:bjtu_plugins@127.0.0.1:5432/bjtu_plugins',
    githubClientId: options.allowMissingOAuth ? (process.env.GITHUB_CLIENT_ID?.trim() || 'test-client') : required('GITHUB_CLIENT_ID'),
    githubClientSecret: options.allowMissingOAuth ? (process.env.GITHUB_CLIENT_SECRET?.trim() || 'test-secret') : required('GITHUB_CLIENT_SECRET'),
    tokenEncryptionKey: key.length === 32 ? key : Buffer.alloc(32),
    sessionCookieName: process.env.SESSION_COOKIE_NAME?.trim() || 'bjtu_plugin_session',
    adminGithubIds: new Set((process.env.ADMIN_GITHUB_IDS || '').split(',').map((item) => item.trim()).filter(Boolean)),
    reservedPluginIds: new Set((process.env.RESERVED_PLUGIN_IDS || '').split(',').map((item) => item.trim()).filter(Boolean)),
    artifactRoot: path.resolve(process.env.ARTIFACT_ROOT?.trim() || path.join(process.cwd(), '.data', 'artifacts')),
    repositoryRoot: path.resolve(process.env.REPOSITORY_ROOT?.trim() || path.join(process.cwd(), '..')),
    pollIntervalMinutes: Math.max(5, integer('POLL_INTERVAL_MINUTES', 30))
  };
}
