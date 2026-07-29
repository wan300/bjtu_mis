import { constants as fsConstants, promises as fs } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type pg from 'pg';
import { loadConfig, type PlatformConfig } from './config.js';
import { decryptSecret } from './crypto.js';
import { createDatabase, transaction, type Database } from './db.js';
import { compareSemVer, id } from './domain.js';
import { downloadGitHubZip, githubJson, GitHubError } from './github.js';
import { validateAndBuildPackage } from './validator.js';

interface ClaimedJob {
  job_id: string;
  submission_id: string;
  user_id: string;
  source_url: string;
  repo_owner: string;
  repo_name: string;
  encrypted_oauth_token: string;
}

interface GitHubRepository {
  id: number;
  private: boolean;
  archived: boolean;
  disabled: boolean;
  default_branch: string;
  owner: {
    id: number;
    login: string;
    type: string;
  };
  permissions?: { push?: boolean; admin?: boolean };
}

interface GitHubCommit { sha: string }

interface ExistingRepositoryBinding {
  plugin_id: string;
  repo_owner: string;
  repo_name: string;
  default_branch: string;
  source_etag: string | null;
  latest_commit: string | null;
  github_repository_id: string | null;
  publisher_owner_id: string | null;
  latest_schema_version: number | null;
  latest_compatibility_state: string | null;
}

export interface PublisherBindingDecision {
  publisherSubjectId: string;
  ownerTransferRequired: boolean;
}

export function validateVersionTransition(
  current: { version: string; schemaVersion: number; dataSchemaVersion: number } | null,
  next: { version: string; dataSchemaVersion: number; migrationEntrypoint: string }
): void {
  if (!current) return;
  if (compareSemVer(next.version, current.version) <= 0) {
    throw new Error(`新版本 ${next.version} 必须高于已发布版本 ${current.version}`);
  }
  if (current.schemaVersion !== 3) return;
  if (next.dataSchemaVersion < current.dataSchemaVersion) {
    throw new Error('data_schema_version 不得降低');
  }
  if (
    next.dataSchemaVersion > current.dataSchemaVersion &&
    !next.migrationEntrypoint.trim()
  ) {
    throw new Error('提升 data_schema_version 必须提供 migration_entrypoint');
  }
}

export function decidePublisherBinding(
  existingPublisherSubjectId: string | null | undefined,
  existingOwnerId: string | null | undefined,
  incomingOwnerId: string | number
): PublisherBindingDecision {
  const normalizedIncomingOwnerId = String(incomingOwnerId).trim();
  if (!/^[1-9]\d*$/.test(normalizedIncomingOwnerId)) {
    throw new Error('GitHub owner numeric ID is invalid');
  }
  const normalizedExistingOwnerId = existingOwnerId?.trim() || null;
  if (normalizedExistingOwnerId !== null && !/^[1-9]\d*$/.test(normalizedExistingOwnerId)) {
    throw new Error('Stored GitHub owner numeric ID is invalid');
  }
  const publisherSubjectId = existingPublisherSubjectId?.trim() ||
    `github-owner:${normalizedExistingOwnerId || normalizedIncomingOwnerId}`;
  return {
    publisherSubjectId,
    ownerTransferRequired: normalizedExistingOwnerId !== null &&
      normalizedExistingOwnerId !== normalizedIncomingOwnerId
  };
}

async function claimJob(db: Database): Promise<ClaimedJob | null> {
  return transaction(db, async (client) => {
    const result = await client.query<ClaimedJob>(`
      SELECT j.id AS job_id, s.id AS submission_id, s.user_id, s.source_url, s.repo_owner, s.repo_name,
             u.encrypted_oauth_token
      FROM validation_jobs j
      JOIN submissions s ON s.id = j.submission_id
      JOIN users u ON u.id = s.user_id
      WHERE j.status = 'queued'
      ORDER BY j.created_at
      FOR UPDATE OF j SKIP LOCKED
      LIMIT 1
    `);
    const job = result.rows[0];
    if (!job) return null;
    await client.query("UPDATE validation_jobs SET status='running', attempt=attempt+1, started_at=now() WHERE id=$1", [job.job_id]);
    await client.query("UPDATE submissions SET status='validating', updated_at=now() WHERE id=$1", [job.submission_id]);
    return job;
  });
}

function manifestValue(manifest: Record<string, unknown>, key: string): string {
  const value = manifest[key];
  return typeof value === 'string' ? value : '';
}

function manifestInteger(manifest: Record<string, unknown>, key: string): number {
  const value = manifest[key];
  return typeof value === 'number' && Number.isSafeInteger(value) ? value : 0;
}

async function freezePublisherTransfer(
  db: Database,
  pluginId: string,
  owner: GitHubRepository['owner']
): Promise<void> {
  await transaction(db, async (client) => {
    await client.query(`
      UPDATE plugins
      SET pending_owner_id=$2, pending_owner_login=$3, pending_owner_type=$4,
        publisher_transfer_status='pending', updated_at=now()
      WHERE plugin_id=$1
    `, [pluginId, String(owner.id), owner.login, owner.type]);
    await client.query(`
      INSERT INTO audit_log(id, action, plugin_id, metadata_json)
      VALUES($1, 'publisher_transfer_detected', $2, $3::jsonb)
    `, [id(), pluginId, JSON.stringify({
      pendingOwnerId: String(owner.id),
      pendingOwnerLogin: owner.login,
      pendingOwnerType: owner.type
    })]);
  });
}

async function latestVersion(client: pg.PoolClient, pluginId: string): Promise<{
  id: string;
  version: string;
  commit_sha: string;
  manifest_schema_version: number;
  data_schema_version: number;
} | null> {
  const result = await client.query<{
    id: string;
    version: string;
    commit_sha: string;
    manifest_schema_version: number;
    data_schema_version: number;
  }>(`
    SELECT v.id, v.version, v.commit_sha, v.manifest_schema_version, v.data_schema_version
    FROM plugins p JOIN plugin_versions v ON v.id = p.latest_version_id
    WHERE p.plugin_id=$1
  `, [pluginId]);
  return result.rows[0] ?? null;
}

async function finishNoChange(
  db: Database,
  job: ClaimedJob,
  pluginId: string,
  commitSha: string,
  repository: GitHubRepository,
  defaultBranch: string,
  etag?: string
): Promise<void> {
  await transaction(db, async (client) => {
    await client.query(`
      UPDATE plugins SET source_url=$2, repo_owner=$3, repo_name=$4, default_branch=$5,
        github_repository_id=$6, publisher_owner_id=$7, publisher_owner_login=$8,
        publisher_owner_type=$9, source_etag=COALESCE($10, source_etag), source_failure_count=0,
        last_checked_at=now(), updated_at=now()
      WHERE plugin_id=$1
    `, [
      pluginId,
      job.source_url,
      job.repo_owner,
      job.repo_name,
      defaultBranch,
      String(repository.id),
      String(repository.owner.id),
      repository.owner.login,
      repository.owner.type,
      etag ?? null
    ]);
    await client.query("UPDATE submissions SET status='published', plugin_id=$2, commit_sha=$3, error_text=NULL, updated_at=now() WHERE id=$1", [job.submission_id, pluginId, commitSha]);
    await client.query("UPDATE validation_jobs SET status='completed', finished_at=now(), diagnostics_json=$2::jsonb WHERE id=$1", [job.job_id, JSON.stringify({ unchanged: true })]);
  });
}

async function markFailure(db: Database, job: ClaimedJob, error: unknown): Promise<void> {
  const message = (error instanceof Error ? error.message : String(error)).slice(0, 2000);
  await transaction(db, async (client) => {
    await client.query("UPDATE submissions SET status='rejected', error_text=$2, updated_at=now() WHERE id=$1", [job.submission_id, message]);
    await client.query("UPDATE validation_jobs SET status='failed', finished_at=now(), diagnostics_json=$2::jsonb WHERE id=$1", [job.job_id, JSON.stringify({ error: message })]);
    if (error instanceof GitHubError && (error.status === 404 || error.status === 451)) {
      const result = await client.query<{ plugin_id: string; source_failure_count: number }>(`
        UPDATE plugins SET source_failure_count=source_failure_count+1, last_checked_at=now(), updated_at=now()
        WHERE lower(repo_owner)=lower($1) AND lower(repo_name)=lower($2)
        RETURNING plugin_id, source_failure_count
      `, [job.repo_owner, job.repo_name]);
      const plugin = result.rows[0];
      if (plugin && plugin.source_failure_count >= 3) {
        await client.query("UPDATE plugins SET status='unlisted' WHERE plugin_id=$1", [plugin.plugin_id]);
        await client.query("INSERT INTO audit_log(id, action, plugin_id, metadata_json) VALUES($1, 'source_auto_unlisted', $2, $3::jsonb)", [id(), plugin.plugin_id, JSON.stringify({ reason: message })]);
      }
    }
  });
}

async function pruneVersions(client: pg.PoolClient, pluginId: string): Promise<string[]> {
  const result = await client.query<{ id: string; artifact_path: string; icon_path: string }>(`
    SELECT id, artifact_path, icon_path FROM plugin_versions
    WHERE plugin_id=$1 ORDER BY published_at DESC OFFSET 2
  `, [pluginId]);
  if (result.rows.length) {
    await client.query('DELETE FROM plugin_versions WHERE id = ANY($1::text[])', [result.rows.map((row) => row.id)]);
  }
  return result.rows.flatMap((row) => [row.artifact_path, row.icon_path]);
}

export async function publishStagedFile(sourcePath: string, targetPath: string): Promise<void> {
  const targetDirectory = path.dirname(targetPath);
  const temporaryPath = path.join(targetDirectory, `.${path.basename(targetPath)}.${id()}.tmp`);
  await fs.mkdir(targetDirectory, { recursive: true });
  try {
    await fs.copyFile(sourcePath, temporaryPath, fsConstants.COPYFILE_EXCL);
    await fs.rename(temporaryPath, targetPath);
  } catch (error) {
    await fs.rm(temporaryPath, { force: true }).catch(() => undefined);
    throw error;
  }
}

export async function runOneValidation(db: Database, config: PlatformConfig): Promise<boolean> {
  const job = await claimJob(db);
  if (!job) return false;
  const workRoot = await fs.mkdtemp(path.join(os.tmpdir(), 'bjtu-plugin-validation-'));
  const sourceZip = path.join(workRoot, 'source.zip');
  const stagedArtifact = path.join(workRoot, 'artifact.zip');
  const stagedIcon = path.join(workRoot, 'icon');
  try {
    const token = decryptSecret(job.encrypted_oauth_token, config.tokenEncryptionKey);
    const existingByRepo = await db.query<ExistingRepositoryBinding>(`
      SELECT p.plugin_id, p.repo_owner, p.repo_name, p.default_branch, p.source_etag,
        p.github_repository_id, p.publisher_owner_id,
        v.commit_sha AS latest_commit, v.manifest_schema_version AS latest_schema_version,
        v.compatibility_state AS latest_compatibility_state
      FROM plugins p LEFT JOIN plugin_versions v ON v.id=p.latest_version_id
      WHERE lower(p.repo_owner)=lower($1) AND lower(p.repo_name)=lower($2)
    `, [job.repo_owner, job.repo_name]);
    let existingRepo = existingByRepo.rows[0];
    const repoResponse = await githubJson<GitHubRepository>(
      `/repos/${encodeURIComponent(job.repo_owner)}/${encodeURIComponent(job.repo_name)}`,
      token
    );
    const repository = repoResponse.data;
    if (!repository) throw new Error('GitHub 仓库元数据为空');
    if (!repository.owner) throw new Error('GitHub 仓库缺少 owner 元数据');
    if (repository.private || repository.disabled) throw new GitHubError('仓库不是可发布的公开仓库', 404);
    if (repository.archived) throw new Error('已归档仓库不能提交新版本');
    const permissions = repository.permissions;
    if (!permissions?.push && !permissions?.admin) {
      throw new Error('无法证明当前 GitHub 用户具有仓库推送或管理权限');
    }
    const publisherOwnerId = String(repository.owner.id);
    if (!/^\d+$/.test(publisherOwnerId)) throw new Error('GitHub owner 数值 ID 无效');
    const githubRepositoryId = String(repository.id);
    if (!/^[1-9]\d*$/.test(githubRepositoryId)) throw new Error('GitHub repository 数值 ID 无效');
    if (!existingRepo) {
      const existingByRepositoryId = await db.query<ExistingRepositoryBinding>(`
        SELECT p.plugin_id, p.repo_owner, p.repo_name, p.default_branch, p.source_etag,
          p.github_repository_id, p.publisher_owner_id,
          v.commit_sha AS latest_commit, v.manifest_schema_version AS latest_schema_version,
          v.compatibility_state AS latest_compatibility_state
        FROM plugins p LEFT JOIN plugin_versions v ON v.id=p.latest_version_id
        WHERE p.github_repository_id=$1
      `, [githubRepositoryId]);
      existingRepo = existingByRepositoryId.rows[0];
    }
    if (
      existingRepo?.github_repository_id &&
      existingRepo.github_repository_id !== githubRepositoryId
    ) {
      throw new Error('GitHub 仓库路径已指向不同的不可变 repository ID');
    }
    const repositoryBinding = decidePublisherBinding(
      null,
      existingRepo?.publisher_owner_id,
      publisherOwnerId
    );
    if (existingRepo && repositoryBinding.ownerTransferRequired) {
      await freezePublisherTransfer(db, existingRepo.plugin_id, repository.owner);
      throw new Error('检测到仓库 owner 转移；更新已冻结，需管理员批准 publisher transfer');
    }
    const defaultBranch = repository.default_branch.trim() || existingRepo?.default_branch;
    if (!defaultBranch) throw new Error('GitHub 仓库缺少默认分支');
    const commit = (await githubJson<GitHubCommit>(
      `/repos/${encodeURIComponent(job.repo_owner)}/${encodeURIComponent(job.repo_name)}/commits/${encodeURIComponent(defaultBranch)}`,
      token
    )).data;
    const commitSha = commit?.sha?.trim() ?? '';
    if (!/^[a-f0-9]{40}$/i.test(commitSha)) throw new Error('GitHub 返回的 commit SHA 无效');
    if (existingRepo?.latest_commit === commitSha &&
      existingRepo.latest_schema_version === 3 &&
      existingRepo.latest_compatibility_state === 'compatible') {
      await finishNoChange(
        db,
        job,
        existingRepo.plugin_id,
        commitSha,
        repository,
        defaultBranch,
        repoResponse.etag
      );
      return true;
    }
    await downloadGitHubZip(job.repo_owner, job.repo_name, commitSha, token, sourceZip, 25 * 1024 * 1024);
    const validated = await validateAndBuildPackage({
      sourceZip,
      workRoot,
      repositoryRoot: config.repositoryRoot,
      artifactPath: stagedArtifact,
      iconPath: stagedIcon
    });
    const pluginId = manifestValue(validated.manifest, 'id');
    const version = manifestValue(validated.manifest, 'version');
    const dataSchemaVersion = manifestInteger(validated.manifest, 'data_schema_version');
    if (config.reservedPluginIds.has(pluginId)) throw new Error(`插件 ID 已由内置插件保留：${pluginId}`);
    if (existingRepo && existingRepo.plugin_id !== pluginId) {
      throw new Error(
        `同一 GitHub repository 不能从 ${existingRepo.plugin_id} 改为新的插件 ID ${pluginId}`
      );
    }

    const collision = await db.query<{
      plugin_id: string;
      repo_owner: string;
      repo_name: string;
      github_repository_id: string | null;
      publisher_subject_id: string | null;
      publisher_owner_id: string | null;
    }>(`
      SELECT plugin_id, repo_owner, repo_name, github_repository_id,
        publisher_subject_id, publisher_owner_id
      FROM plugins WHERE plugin_id=$1
    `, [pluginId]);
    const existingPlugin = collision.rows[0];
    if (
      existingPlugin?.github_repository_id &&
      existingPlugin.github_repository_id !== githubRepositoryId
    ) {
      throw new Error(`插件 ID 已由其他 GitHub repository 占用：${pluginId}`);
    }
    const pluginBinding = decidePublisherBinding(
      existingPlugin?.publisher_subject_id,
      existingPlugin?.publisher_owner_id,
      publisherOwnerId
    );
    if (pluginBinding.ownerTransferRequired) {
      throw new Error(`插件 ID 已由其他 publisher subject 占用：${pluginId}`);
    }
    if (existingPlugin && !existingPlugin.publisher_owner_id &&
      (existingPlugin.repo_owner.toLowerCase() !== job.repo_owner.toLowerCase() ||
        existingPlugin.repo_name.toLowerCase() !== job.repo_name.toLowerCase())) {
      throw new Error(`插件 ID 已由其他 legacy 仓库占用：${pluginId}`);
    }
    if (existingPlugin) {
      const current = await transaction(db, (client) => latestVersion(client, pluginId));
      validateVersionTransition(
        current && {
          version: current.version,
          schemaVersion: current.manifest_schema_version,
          dataSchemaVersion: current.data_schema_version
        },
        {
          version,
          dataSchemaVersion,
          migrationEntrypoint: manifestValue(validated.manifest, 'migration_entrypoint')
        }
      );
    }

    const artifactDirectory = path.join(config.artifactRoot, pluginId, commitSha);
    const finalArtifact = path.join(artifactDirectory, 'plugin.zip');
    const iconExtension = path.extname(manifestValue(validated.manifest, 'icon')).toLowerCase().slice(0, 10) || '.bin';
    const finalIcon = path.join(artifactDirectory, `icon${iconExtension}`);
    const versionId = id();
    const publisherSubjectId = pluginBinding.publisherSubjectId;
    const runtimeVersion = manifestInteger(validated.manifest, 'runtime_version');
    const minRuntimeVersion = manifestInteger(validated.manifest, 'min_runtime_version');
    let staleFiles: string[] = [];
    try {
      await fs.rm(artifactDirectory, { recursive: true, force: true });
      await fs.mkdir(artifactDirectory, { recursive: true });
      await publishStagedFile(stagedArtifact, finalArtifact);
      await publishStagedFile(stagedIcon, finalIcon);
      staleFiles = await transaction(db, async (client) => {
        const statusResult = await client.query<{ admin_suspended: boolean; status: string }>('SELECT admin_suspended, status FROM plugins WHERE plugin_id=$1', [pluginId]);
        const status = statusResult.rows[0]?.admin_suspended ? 'unlisted' : 'published';
        await client.query(`
          INSERT INTO plugins(plugin_id, submitter_user_id, source_url, repo_owner, repo_name, default_branch, status,
            latest_version_id, source_etag, source_failure_count, last_checked_at, github_repository_id, publisher_subject_id,
            publisher_owner_id, publisher_owner_login, publisher_owner_type, publisher_transfer_status)
          VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,0,now(),$10,$11,$12,$13,$14,'none')
          ON CONFLICT(plugin_id) DO UPDATE SET submitter_user_id=excluded.submitter_user_id,
            source_url=excluded.source_url, repo_owner=excluded.repo_owner, repo_name=excluded.repo_name,
            default_branch=excluded.default_branch,
            status=CASE WHEN plugins.admin_suspended THEN 'unlisted' ELSE 'published' END,
            latest_version_id=excluded.latest_version_id, source_etag=excluded.source_etag,
            source_failure_count=0, last_checked_at=now(),
            github_repository_id=excluded.github_repository_id,
            publisher_subject_id=COALESCE(plugins.publisher_subject_id, excluded.publisher_subject_id),
            publisher_owner_id=excluded.publisher_owner_id,
            publisher_owner_login=excluded.publisher_owner_login,
            publisher_owner_type=excluded.publisher_owner_type,
            pending_owner_id=NULL, pending_owner_login=NULL, pending_owner_type=NULL,
            publisher_transfer_status='none', updated_at=now()
        `, [pluginId, job.user_id, job.source_url, job.repo_owner, job.repo_name, defaultBranch, status,
          versionId, repoResponse.etag ?? null, githubRepositoryId, publisherSubjectId, publisherOwnerId,
          repository.owner.login, repository.owner.type]);
        await client.query(`
          INSERT INTO plugin_versions(id, plugin_id, version, commit_sha, manifest_json, warnings_json,
            archive_sha256, package_digest_sha256, package_bytes, package_file_count, artifact_path, icon_path,
            manifest_schema_version, runtime_version, min_runtime_version, data_schema_version,
            compatibility_state, verification_level)
          VALUES($1,$2,$3,$4,$5::jsonb,$6::jsonb,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,'compatible','automated')
        `, [versionId, pluginId, version, commitSha, JSON.stringify(validated.manifest), JSON.stringify(validated.warnings),
          validated.archiveSha256, validated.packageDigestSha256, validated.archiveBytes,
          validated.packageFileCount, finalArtifact, finalIcon, 3, runtimeVersion,
          minRuntimeVersion, dataSchemaVersion]);
        await client.query("UPDATE submissions SET status='published', plugin_id=$2, commit_sha=$3, error_text=NULL, updated_at=now() WHERE id=$1", [job.submission_id, pluginId, commitSha]);
        await client.query("UPDATE validation_jobs SET status='completed', finished_at=now(), diagnostics_json=$2::jsonb WHERE id=$1", [job.job_id, JSON.stringify({ warnings: validated.warnings })]);
        await client.query("INSERT INTO audit_log(id, actor_user_id, action, plugin_id, metadata_json) VALUES($1,$2,'version_published',$3,$4::jsonb)", [id(), job.user_id, pluginId, JSON.stringify({ version, commitSha })]);
        return pruneVersions(client, pluginId);
      });
    } catch (error) {
      await fs.rm(artifactDirectory, { recursive: true, force: true });
      throw error;
    }
    await Promise.all(staleFiles.map((file) => fs.rm(file, { force: true }).catch(() => undefined)));
    return true;
  } catch (error) {
    await markFailure(db, job, error);
    return true;
  } finally {
    await fs.rm(workRoot, { recursive: true, force: true });
  }
}

export async function enqueueDueRevalidations(db: Database, config: PlatformConfig): Promise<number> {
  const due = await db.query<{ plugin_id: string; submitter_user_id: string; source_url: string; repo_owner: string; repo_name: string }>(`
    SELECT p.plugin_id, p.submitter_user_id, p.source_url, p.repo_owner, p.repo_name
    FROM plugins p
    WHERE p.status='published' AND NOT p.admin_suspended
      AND (p.last_checked_at IS NULL OR p.last_checked_at < now() - ($1 || ' minutes')::interval)
      AND NOT EXISTS (
        SELECT 1 FROM submissions s JOIN validation_jobs j ON j.submission_id=s.id
        WHERE lower(s.repo_owner)=lower(p.repo_owner) AND lower(s.repo_name)=lower(p.repo_name)
          AND j.status IN ('queued','running')
      )
    ORDER BY p.last_checked_at NULLS FIRST LIMIT 20
  `, [config.pollIntervalMinutes]);
  for (const plugin of due.rows) {
    await transaction(db, async (client) => {
      const submissionId = id();
      await client.query(`INSERT INTO submissions(id,user_id,source_url,repo_owner,repo_name,status,plugin_id)
        VALUES($1,$2,$3,$4,$5,'queued',$6)`, [submissionId, plugin.submitter_user_id, plugin.source_url, plugin.repo_owner, plugin.repo_name, plugin.plugin_id]);
      await client.query("INSERT INTO validation_jobs(id,submission_id,status) VALUES($1,$2,'queued')", [id(), submissionId]);
      await client.query('UPDATE plugins SET last_checked_at=now() WHERE plugin_id=$1', [plugin.plugin_id]);
    });
  }
  return due.rows.length;
}

async function main(): Promise<void> {
  const config = loadConfig();
  const db = createDatabase(config.databaseUrl);
  const stop = async () => { await db.end(); process.exit(0); };
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
  while (true) {
    const processed = await runOneValidation(db, config).catch((error) => {
      process.stderr.write(`worker error: ${error instanceof Error ? error.message : String(error)}\n`);
      return false;
    });
    if (!processed) {
      await enqueueDueRevalidations(db, config).catch((error) => process.stderr.write(`poll error: ${error instanceof Error ? error.message : String(error)}\n`));
      await new Promise((resolve) => setTimeout(resolve, 5000));
    }
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  await main();
}
