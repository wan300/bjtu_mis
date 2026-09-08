import type { FastifyInstance } from 'fastify';
import type { RouteContext } from './context.js';
import { transaction, type Database } from '../db.js';
import { id, parseGitHubRepositoryUrl } from '../domain.js';
import { requireUser, isAdmin } from './auth.js';
import { apiError } from './context.js';

export async function createSubmission(
  db: Database,
  userId: string,
  sourceUrl: string,
  contractProfile: 'legacy' | 'contract_v1' = 'contract_v1'
): Promise<string> {
  const repository = parseGitHubRepositoryUrl(sourceUrl);
  const active = await db.query(`SELECT 1 FROM submissions
    WHERE lower(repo_owner)=lower($1) AND lower(repo_name)=lower($2)
      AND status IN ('queued','validating') LIMIT 1`, [repository.owner, repository.repo]);
  if (active.rowCount) throw new Error('该仓库已有校验任务正在进行');
  const daily = await db.query<{ count: string }>("SELECT count(*)::text AS count FROM submissions WHERE user_id=$1 AND created_at > now() - interval '1 day'", [userId]);
  if (Number(daily.rows[0]?.count ?? 0) >= 10) throw new Error('每天最多提交或重校验 10 次');
  const submissionId = id();
  try {
    await transaction(db, async (client) => {
      await client.query(`INSERT INTO submissions(
          id,user_id,source_url,repo_owner,repo_name,status,contract_profile
        ) VALUES($1,$2,$3,$4,$5,'queued',$6)`, [
        submissionId,
        userId,
        repository.canonicalUrl,
        repository.owner,
        repository.repo,
        contractProfile
      ]);
      await client.query("INSERT INTO validation_jobs(id,submission_id,status) VALUES($1,$2,'queued')", [id(), submissionId]);
    });
  } catch (error) {
    if (isConstraintViolation(error, 'submissions_active_repository_idx')) {
      throw new Error('该仓库已有校验任务正在进行');
    }
    throw error;
  }
  return submissionId;
}

function isConstraintViolation(error: unknown, constraint: string): boolean {
  if (!error || typeof error !== 'object') return false;
  const databaseError = error as { code?: unknown; constraint?: unknown };
  return databaseError.code === '23505' && databaseError.constraint === constraint;
}

export function registerSubmissionsRoutes(app: FastifyInstance, { db, config }: RouteContext) {
  app.post('/api/v3/submissions', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config);
    if (!auth) return;
    try {
      const submissionId = await createSubmission(
        db,
        auth.user.id,
        String((request.body as { repositoryUrl?: string })?.repositoryUrl ?? ''),
        'contract_v1'
      );
      return reply.code(202).send({
        apiVersion: 3,
        id: submissionId,
        status: 'queued',
        requiredSchemaVersion: 3,
        requiredManifest: 'bjtu-plugin.json',
        requiredMarketplace: 'bjtu-marketplace.json',
        contractProfile: 'contract_v1'
      });
    } catch (error) {
      return apiError(
        reply,
        400,
        'submission_rejected',
        error instanceof Error ? error.message : String(error)
      );
    }
  });

  app.get('/api/v3/submissions/:id', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config, false);
    if (!auth) return;
    const submissionId = (request.params as { id: string }).id;
    const result = await db.query(
      `SELECT id,source_url,status,plugin_id,commit_sha,error_text,created_at,updated_at,
        user_id,contract_profile FROM submissions WHERE id=$1`,
      [submissionId]
    );
    const row = result.rows[0];
    if (
      !row ||
      row.contract_profile !== 'contract_v1' ||
      (row.user_id !== auth.user.id && !isAdmin(auth.user, config))
    ) {
      return apiError(reply, 404, 'submission_not_found', '投稿不存在');
    }
    const { user_id: _private, ...publicRow } = row;
    return { apiVersion: 3, ...publicRow };
  });

  app.get('/api/v2/submissions/:id', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config, false);
    if (!auth) return;
    const submissionId = (request.params as { id: string }).id;
    const result = await db.query(
      'SELECT id,source_url,status,plugin_id,commit_sha,error_text,created_at,updated_at,user_id FROM submissions WHERE id=$1',
      [submissionId]
    );
    const row = result.rows[0];
    if (!row || (row.user_id !== auth.user.id && !isAdmin(auth.user, config))) {
      return apiError(reply, 404, 'submission_not_found', '投稿不存在');
    }
    const { user_id: _private, ...publicRow } = row;
    return { apiVersion: 2, ...publicRow };
  });

  app.get('/api/v1/submissions/:id', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config, false);
    if (!auth) return;
    const submissionId = (request.params as { id: string }).id;
    const result = await db.query('SELECT id,source_url,status,plugin_id,commit_sha,error_text,created_at,updated_at,user_id FROM submissions WHERE id=$1', [submissionId]);
    const row = result.rows[0];
    if (!row || (row.user_id !== auth.user.id && !isAdmin(auth.user, config))) return apiError(reply, 404, 'submission_not_found', '投稿不存在');
    const { user_id: _private, ...publicRow } = row;
    return publicRow;
  });
}
