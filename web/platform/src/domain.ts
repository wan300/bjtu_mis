import { randomUUID } from 'node:crypto';

export interface GitHubRepositoryRef {
  owner: string;
  repo: string;
  canonicalUrl: string;
}

export function parseGitHubRepositoryUrl(value: string): GitHubRepositoryRef {
  let url: URL;
  try {
    url = new URL(value.trim());
  } catch {
    throw new Error('请输入有效的 GitHub 公开仓库链接');
  }
  if (url.protocol !== 'https:' || url.hostname.toLowerCase() !== 'github.com' || url.username || url.password || url.search || url.hash) {
    throw new Error('仅支持 https://github.com/{owner}/{repo} 仓库根链接');
  }
  const parts = url.pathname.split('/').filter(Boolean);
  if (parts.length !== 2 || parts[1]?.endsWith('.git')) throw new Error('仅支持 GitHub 仓库根链接');
  const owner = parts[0] ?? '';
  const repo = parts[1] ?? '';
  const pattern = /^[A-Za-z0-9_.-]+$/;
  if (!pattern.test(owner) || !pattern.test(repo)) throw new Error('GitHub owner 或 repo 格式无效');
  return { owner, repo, canonicalUrl: `https://github.com/${owner}/${repo}` };
}

export function compareSemVer(left: string, right: string): number {
  const parse = (value: string) => {
    const match = /^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$/.exec(value);
    if (!match) throw new Error(`Invalid semantic version: ${value}`);
    return {
      core: [Number(match[1]), Number(match[2]), Number(match[3])] as const,
      prerelease: match[4]?.split('.') ?? []
    };
  };
  const a = parse(left);
  const b = parse(right);
  for (let index = 0; index < 3; index += 1) {
    const difference = (a.core[index] ?? 0) - (b.core[index] ?? 0);
    if (difference) return Math.sign(difference);
  }
  if (!a.prerelease.length && !b.prerelease.length) return 0;
  if (!a.prerelease.length) return 1;
  if (!b.prerelease.length) return -1;
  for (let index = 0; index < Math.max(a.prerelease.length, b.prerelease.length); index += 1) {
    const x = a.prerelease[index];
    const y = b.prerelease[index];
    if (x === undefined) return -1;
    if (y === undefined) return 1;
    if (x === y) continue;
    const xNumber = /^\d+$/.test(x) ? Number(x) : null;
    const yNumber = /^\d+$/.test(y) ? Number(y) : null;
    if (xNumber !== null && yNumber !== null) return Math.sign(xNumber - yNumber);
    if (xNumber !== null) return -1;
    if (yNumber !== null) return 1;
    return x.localeCompare(y);
  }
  return 0;
}

export function id(): string {
  return randomUUID();
}
