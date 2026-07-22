import { createWriteStream } from 'node:fs';
import { once } from 'node:events';

export class GitHubError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
  }
}

function headers(token: string, etag?: string): Record<string, string> {
  const result: Record<string, string> = {
    Accept: 'application/vnd.github+json',
    'User-Agent': 'bjtu-plugin-platform',
    'X-GitHub-Api-Version': '2022-11-28'
  };
  if (token) result.Authorization = `Bearer ${token}`;
  if (etag) result['If-None-Match'] = etag;
  return result;
}

export async function githubJson<T>(path: string, token: string, etag?: string): Promise<{ status: number; data?: T; etag?: string }> {
  const response = await fetch(`https://api.github.com${path}`, { headers: headers(token, etag) });
  if (response.status === 304) return { status: 304, etag: response.headers.get('etag') ?? etag };
  if (!response.ok) throw new GitHubError(`GitHub API 请求失败：HTTP ${response.status}`, response.status);
  return {
    status: response.status,
    data: await response.json() as T,
    etag: response.headers.get('etag') ?? undefined
  };
}

export async function downloadGitHubZip(owner: string, repo: string, commitSha: string, token: string, target: string, maxBytes: number): Promise<number> {
  const response = await fetch(`https://api.github.com/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/zipball/${encodeURIComponent(commitSha)}`, {
    headers: headers(token),
    redirect: 'follow'
  });
  if (!response.ok || !response.body) throw new GitHubError(`GitHub 下载失败：HTTP ${response.status}`, response.status);
  const output = createWriteStream(target, { flags: 'wx' });
  let total = 0;
  try {
    for await (const rawChunk of response.body as unknown as AsyncIterable<Uint8Array>) {
      const chunk = Buffer.from(rawChunk);
      total += chunk.length;
      if (total > maxBytes) throw new Error('插件源代码压缩包超过 25 MiB 限制');
      if (!output.write(chunk)) await once(output, 'drain');
    }
    output.end();
    await once(output, 'close');
    return total;
  } catch (error) {
    output.destroy();
    throw error;
  }
}
