import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import test from 'node:test';
import { decryptSecret, encryptSecret } from '../../src/crypto.js';
import { loadConfig } from '../../src/config.js';
import { compareSemVer, parseGitHubRepositoryUrl } from '../../src/domain.js';

test('accepts only public GitHub repository root URLs', () => {
  assert.deepEqual(parseGitHubRepositoryUrl('https://github.com/openai/codex'), {
    owner: 'openai',
    repo: 'codex',
    canonicalUrl: 'https://github.com/openai/codex'
  });
  for (const invalid of [
    'http://github.com/openai/codex',
    'https://github.com/openai/codex/tree/main',
    'https://github.com/openai/codex.git',
    'https://example.com/openai/codex'
  ]) {
    assert.throws(() => parseGitHubRepositoryUrl(invalid));
  }
});

test('compares semantic versions including prereleases', () => {
  assert.equal(compareSemVer('1.2.0', '1.1.9'), 1);
  assert.equal(compareSemVer('1.2.0-beta.2', '1.2.0-beta.10'), -1);
  assert.equal(compareSemVer('1.2.0', '1.2.0-rc.1'), 1);
  assert.equal(compareSemVer('1.2.0+build.1', '1.2.0+build.2'), 0);
});

test('orders SemVer identifiers by ASCII and without numeric precision loss', () => {
  const orderedPairs = [
    ['1.0.0-B', '1.0.0-a'],
    ['1.0.0-alpha.9007199254740992', '1.0.0-alpha.9007199254740993'],
    ['9007199254740992.0.0', '9007199254740993.0.0'],
    ['1.0.0-9', '1.0.0-10'],
    ['1.0.0-10', '1.0.0-A'],
    ['1.0.0-alpha', '1.0.0-alpha.1'],
    ['1.0.0-alpha.1', '1.0.0-alpha.beta'],
    ['1.0.0-beta.11', '1.0.0-rc.1']
  ];
  for (const [left, right] of orderedPairs) {
    assert.equal(compareSemVer(left!, right!), -1, `${left} < ${right}`);
    assert.equal(compareSemVer(right!, left!), 1, `${right} > ${left}`);
    assert.equal(compareSemVer(left!, left!), 0);
  }
});

test('rejects invalid SemVer identifiers', () => {
  for (const version of ['01.0.0', '1.0.0-01', '1.0.0-alpha..1', '1.0.0+build..1', '1.0.0-']) {
    assert.throws(() => compareSemVer(version, '1.0.0'), /Invalid semantic version/);
  }
});

test('encrypts OAuth tokens with authenticated encryption', () => {
  const key = randomBytes(32);
  const encrypted = encryptSecret('github-token', key);
  assert.notEqual(encrypted, 'github-token');
  assert.equal(decryptSecret(encrypted, key), 'github-token');
  const tampered = Buffer.from(encrypted, 'base64');
  tampered[tampered.length - 1] = (tampered.at(-1) ?? 0) ^ 1;
  assert.throws(() => decryptSecret(tampered.toString('base64'), key));
});

test('production configuration requires an explicit 32 byte token key', () => {
  const previous = process.env.TOKEN_ENCRYPTION_KEY_BASE64;
  delete process.env.TOKEN_ENCRYPTION_KEY_BASE64;
  try {
    assert.throws(() => loadConfig(), /TOKEN_ENCRYPTION_KEY_BASE64/);
  } finally {
    if (previous === undefined) delete process.env.TOKEN_ENCRYPTION_KEY_BASE64;
    else process.env.TOKEN_ENCRYPTION_KEY_BASE64 = previous;
  }
});
