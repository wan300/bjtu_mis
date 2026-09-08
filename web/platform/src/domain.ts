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
    const match = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/.exec(value);
    const prerelease = match?.[4]?.split('.') ?? [];
    if (!match || prerelease.some((part) => /^0\d+$/.test(part))) {
      throw new Error(`Invalid semantic version: ${value}`);
    }
    return {
      core: [match[1]!, match[2]!, match[3]!],
      prerelease
    };
  };
  const compareText = (a: string, b: string) => a === b ? 0 : a < b ? -1 : 1;
  // Numeric identifiers have no leading zeroes, so length preserves arbitrary precision.
  const compareNumeric = (a: string, b: string) =>
    Math.sign(a.length - b.length) || compareText(a, b);
  const a = parse(left);
  const b = parse(right);
  for (let index = 0; index < 3; index += 1) {
    const difference = compareNumeric(a.core[index]!, b.core[index]!);
    if (difference) return difference;
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
    const xNumeric = /^\d+$/.test(x);
    const yNumeric = /^\d+$/.test(y);
    if (xNumeric && yNumeric) return compareNumeric(x, y);
    if (xNumeric) return -1;
    if (yNumeric) return 1;
    return compareText(x, y);
  }
  return 0;
}

export function id(): string {
  return randomUUID();
}
