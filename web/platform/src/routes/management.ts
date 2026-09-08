import type { FastifyInstance } from 'fastify';
import type { RouteContext } from './context.js';
import { transaction } from '../db.js';
import { id } from '../domain.js';
import { requireUser, isAdmin } from './auth.js';
import { apiError } from './context.js';
import { createSubmission } from './submissions.js';

export function registerManagementRoutes(app: FastifyInstance, { db, config }: RouteContext) {
  app.get('/api/v1/me/plugins', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config, false);
    if (!auth) return;
    const submissions = await db.query(`SELECT id,source_url,status,plugin_id,commit_sha,error_text,created_at,updated_at
      FROM submissions WHERE user_id=$1 ORDER BY created_at DESC LIMIT 100`, [auth.user.id]);
    return { items: submissions.rows };
  });

  app.get('/api/v1/admin/overview', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config, false);
    if (!auth || !isAdmin(auth.user, config)) return auth ? apiError(reply, 403, 'admin_required', '需要管理员权限') : undefined;
    const [plugins, jobs, reports] = await Promise.all([
      db.query(`SELECT p.plugin_id,p.source_url,p.status,p.admin_suspended,p.updated_at,v.version,v.commit_sha
        FROM plugins p LEFT JOIN plugin_versions v ON v.id=p.latest_version_id ORDER BY p.updated_at DESC LIMIT 200`),
      db.query(`SELECT j.id,j.status,j.attempt,j.diagnostics_json,j.created_at,j.finished_at,s.source_url,s.plugin_id
        FROM validation_jobs j JOIN submissions s ON s.id=j.submission_id ORDER BY j.created_at DESC LIMIT 200`),
      db.query(`SELECT r.id,r.plugin_id,r.reason,r.details,r.status,r.created_at,u.login AS reporter
        FROM reports r JOIN users u ON u.id=r.reporter_user_id ORDER BY r.created_at DESC LIMIT 200`)
    ]);
    return { plugins: plugins.rows, jobs: jobs.rows, reports: reports.rows };
  });

  app.get('/api/v3/me/plugins', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config, false);
    if (!auth) return;
    const submissions = await db.query(`
      SELECT s.id,s.source_url,s.status,s.plugin_id,s.commit_sha,s.error_text,
        s.created_at,s.updated_at,s.contract_profile,
        p.publisher_subject_id,p.publisher_owner_id,p.publisher_owner_login,
        p.publisher_transfer_status
      FROM submissions s LEFT JOIN plugins p ON p.plugin_id=s.plugin_id
      WHERE s.user_id=$1 AND s.contract_profile='contract_v1'
      ORDER BY s.created_at DESC LIMIT 100
    `, [auth.user.id]);
    return { apiVersion: 3, contractProfile: 'contract_v1', items: submissions.rows };
  });

  app.post('/api/v3/plugins/:id/revalidate', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config);
    if (!auth) return;
    const pluginId = (request.params as { id: string }).id;
    const result = await db.query<{
      source_url: string;
      submitter_user_id: string;
      admin_suspended: boolean;
      publisher_transfer_status: string;
    }>(`
      SELECT source_url,submitter_user_id,admin_suspended,publisher_transfer_status
      FROM plugins WHERE plugin_id=$1
    `, [pluginId]);
    const plugin = result.rows[0];
    if (!plugin || plugin.submitter_user_id !== auth.user.id) {
      return apiError(reply, 404, 'plugin_not_found', '插件不存在');
    }
    if (plugin.admin_suspended) {
      return apiError(reply, 409, 'admin_suspended', '插件已被管理员下架');
    }
    if (plugin.publisher_transfer_status === 'pending') {
      return apiError(
        reply,
        409,
        'publisher_transfer_pending',
        'publisher transfer 尚待管理员审批'
      );
    }
    try {
      const submissionId = await createSubmission(
        db,
        auth.user.id,
        plugin.source_url,
        'contract_v1'
      );
      return reply.code(202).send({
        apiVersion: 3,
        contractProfile: 'contract_v1',
        id: submissionId,
        status: 'queued'
      });
    } catch (error) {
      return apiError(
        reply,
        400,
        'revalidation_rejected',
        error instanceof Error ? error.message : String(error)
      );
    }
  });

  app.post('/api/v3/plugins/:id/unpublish', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config);
    if (!auth) return;
    const pluginId = (request.params as { id: string }).id;
    const result = await db.query(`
      UPDATE plugins SET status='withdrawn',updated_at=now()
      WHERE plugin_id=$1 AND submitter_user_id=$2 AND NOT admin_suspended
      RETURNING plugin_id
    `, [pluginId, auth.user.id]);
    if (!result.rowCount) {
      return apiError(reply, 404, 'plugin_not_found', '插件不存在或无法下架');
    }
    await db.query(
      "INSERT INTO audit_log(id,actor_user_id,action,plugin_id) VALUES($1,$2,'developer_unpublished',$3)",
      [id(), auth.user.id, pluginId]
    );
    return { apiVersion: 3, ok: true };
  });

  app.post('/api/v3/plugins/:id/reports', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config);
    if (!auth) return;
    const pluginId = (request.params as { id: string }).id;
    const body = request.body as { reason?: string; details?: string };
    const reason = String(body?.reason ?? '').trim().slice(0, 80);
    const details = String(body?.details ?? '').trim().slice(0, 1000);
    if (!reason) return apiError(reply, 400, 'reason_required', '请选择举报原因');
    const exists = await db.query(`
      SELECT 1 FROM plugins p JOIN plugin_versions v ON v.id=p.latest_version_id
      WHERE p.plugin_id=$1 AND p.status='published'
        AND v.contract_profile='contract_v1'
        AND v.manifest_schema_version=3 AND v.compatibility_state='compatible'
    `, [pluginId]);
    if (!exists.rowCount) return apiError(reply, 404, 'plugin_not_found', '插件不存在');
    await db.query(
      'INSERT INTO reports(id,plugin_id,reporter_user_id,reason,details) VALUES($1,$2,$3,$4,$5)',
      [id(), pluginId, auth.user.id, reason, details]
    );
    return reply.code(201).send({ apiVersion: 3, ok: true });
  });

  app.get('/api/v3/admin/overview', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config, false);
    if (!auth || !isAdmin(auth.user, config)) {
      return auth ? apiError(reply, 403, 'admin_required', '需要管理员权限') : undefined;
    }
    const [plugins, jobs, reports] = await Promise.all([
      db.query(`
        SELECT p.plugin_id,p.source_url,p.status,p.admin_suspended,p.updated_at,
          p.publisher_subject_id,p.publisher_owner_id,p.publisher_owner_login,
          p.pending_owner_id,p.pending_owner_login,p.publisher_transfer_status,
          v.version,v.commit_sha,v.manifest_schema_version,v.compatibility_state,
          v.contract_profile,v.runtime_floor
        FROM plugins p LEFT JOIN plugin_versions v ON v.id=p.latest_version_id
        ORDER BY p.updated_at DESC LIMIT 200
      `),
      db.query(`
        SELECT j.id,j.status,j.attempt,j.diagnostics_json,j.created_at,j.finished_at,
          s.source_url,s.plugin_id,s.contract_profile
        FROM validation_jobs j JOIN submissions s ON s.id=j.submission_id
        ORDER BY j.created_at DESC LIMIT 200
      `),
      db.query(`
        SELECT r.id,r.plugin_id,r.reason,r.details,r.status,r.created_at,u.login AS reporter
        FROM reports r JOIN users u ON u.id=r.reporter_user_id
        ORDER BY r.created_at DESC LIMIT 200
      `)
    ]);
    return { apiVersion: 3, plugins: plugins.rows, jobs: jobs.rows, reports: reports.rows };
  });

  app.post('/api/v3/admin/plugins/:id/unpublish', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config);
    if (!auth || !isAdmin(auth.user, config)) {
      return auth ? apiError(reply, 403, 'admin_required', '需要管理员权限') : undefined;
    }
    const pluginId = (request.params as { id: string }).id;
    const reason = String((request.body as { reason?: string })?.reason ?? '')
      .trim()
      .slice(0, 500);
    const result = await db.query(`
      UPDATE plugins SET status='unlisted',admin_suspended=true,updated_at=now()
      WHERE plugin_id=$1 RETURNING plugin_id
    `, [pluginId]);
    if (!result.rowCount) return apiError(reply, 404, 'plugin_not_found', '插件不存在');
    await db.query(`
      INSERT INTO audit_log(id,actor_user_id,action,plugin_id,metadata_json)
      VALUES($1,$2,'admin_unpublished',$3,$4::jsonb)
    `, [id(), auth.user.id, pluginId, JSON.stringify({ reason })]);
    return { apiVersion: 3, ok: true };
  });

  app.post('/api/v3/admin/plugins/:id/restore', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config);
    if (!auth || !isAdmin(auth.user, config)) {
      return auth ? apiError(reply, 403, 'admin_required', '需要管理员权限') : undefined;
    }
    const pluginId = (request.params as { id: string }).id;
    const result = await db.query(`
      UPDATE plugins SET status='published',admin_suspended=false,source_failure_count=0,
        updated_at=now()
      WHERE plugin_id=$1 AND latest_version_id IS NOT NULL RETURNING plugin_id
    `, [pluginId]);
    if (!result.rowCount) {
      return apiError(reply, 404, 'plugin_not_found', '插件不存在或没有可恢复版本');
    }
    await db.query(
      "INSERT INTO audit_log(id,actor_user_id,action,plugin_id) VALUES($1,$2,'admin_restored',$3)",
      [id(), auth.user.id, pluginId]
    );
    return { apiVersion: 3, ok: true };
  });

  app.post('/api/v3/admin/plugins/:id/publisher-transfer/approve', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config);
    if (!auth || !isAdmin(auth.user, config)) {
      return auth ? apiError(reply, 403, 'admin_required', '需要管理员权限') : undefined;
    }
    const pluginId = (request.params as { id: string }).id;
    const result = await transaction(db, async (client) => {
      const updated = await client.query<{
        publisher_subject_id: string;
        publisher_owner_id: string;
        publisher_owner_login: string;
      }>(`
        UPDATE plugins
        SET publisher_owner_id=pending_owner_id,
          publisher_owner_login=pending_owner_login,
          publisher_owner_type=pending_owner_type,
          repo_owner=pending_owner_login,
          source_url='https://github.com/' || pending_owner_login || '/' || repo_name,
          pending_owner_id=NULL,pending_owner_login=NULL,pending_owner_type=NULL,
          publisher_transfer_status='approved',updated_at=now()
        WHERE plugin_id=$1
          AND publisher_transfer_status='pending'
          AND pending_owner_id IS NOT NULL
        RETURNING publisher_subject_id,publisher_owner_id,publisher_owner_login
      `, [pluginId]);
      const row = updated.rows[0];
      if (!row) return null;
      await client.query(`
        INSERT INTO audit_log(id,actor_user_id,action,plugin_id,metadata_json)
        VALUES($1,$2,'publisher_transfer_approved',$3,$4::jsonb)
      `, [
        id(),
        auth.user.id,
        pluginId,
        JSON.stringify({
          publisherSubjectId: row.publisher_subject_id,
          newOwnerId: row.publisher_owner_id,
          newOwnerLogin: row.publisher_owner_login
        })
      ]);
      return row;
    });
    if (!result) {
      return apiError(
        reply,
        409,
        'publisher_transfer_not_pending',
        '没有待审批的 publisher transfer'
      );
    }
    return { apiVersion: 3, ok: true, publisher: result };
  });

  app.post('/api/v3/admin/plugins/:id/publisher-transfer/reject', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config);
    if (!auth || !isAdmin(auth.user, config)) {
      return auth ? apiError(reply, 403, 'admin_required', '需要管理员权限') : undefined;
    }
    const pluginId = (request.params as { id: string }).id;
    const reason = String((request.body as { reason?: string })?.reason ?? '')
      .trim()
      .slice(0, 500);
    const result = await transaction(db, async (client) => {
      const pending = await client.query(`
        UPDATE plugins
        SET pending_owner_id=NULL,pending_owner_login=NULL,pending_owner_type=NULL,
          publisher_transfer_status='rejected',updated_at=now()
        WHERE plugin_id=$1 AND publisher_transfer_status='pending'
        RETURNING plugin_id
      `, [pluginId]);
      if (!pending.rowCount) return false;
      await client.query(`
        INSERT INTO audit_log(id,actor_user_id,action,plugin_id,metadata_json)
        VALUES($1,$2,'publisher_transfer_rejected',$3,$4::jsonb)
      `, [id(), auth.user.id, pluginId, JSON.stringify({ reason })]);
      return true;
    });
    if (!result) {
      return apiError(
        reply,
        409,
        'publisher_transfer_not_pending',
        '没有待审批的 publisher transfer'
      );
    }
    return { apiVersion: 3, ok: true };
  });

  app.post('/api/v3/admin/reports/:id/resolve', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config);
    if (!auth || !isAdmin(auth.user, config)) {
      return auth ? apiError(reply, 403, 'admin_required', '需要管理员权限') : undefined;
    }
    const reportId = (request.params as { id: string }).id;
    const status =
      (request.body as { status?: string })?.status === 'dismissed'
        ? 'dismissed'
        : 'resolved';
    const result = await db.query(
      'UPDATE reports SET status=$2,resolved_at=now() WHERE id=$1 RETURNING id',
      [reportId, status]
    );
    if (!result.rowCount) return apiError(reply, 404, 'report_not_found', '举报不存在');
    return { apiVersion: 3, ok: true };
  });

  app.get('/api/v2/me/plugins', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config, false);
    if (!auth) return;
    const submissions = await db.query(`
      SELECT s.id,s.source_url,s.status,s.plugin_id,s.commit_sha,s.error_text,s.created_at,s.updated_at,
        p.publisher_subject_id,p.publisher_owner_id,p.publisher_owner_login,p.publisher_transfer_status
      FROM submissions s LEFT JOIN plugins p ON p.plugin_id=s.plugin_id
      WHERE s.user_id=$1 ORDER BY s.created_at DESC LIMIT 100
    `, [auth.user.id]);
    return { apiVersion: 2, items: submissions.rows };
  });

  app.get('/api/v2/admin/overview', async (request, reply) => {
    const auth = await requireUser(request, reply, db, config, false);
    if (!auth || !isAdmin(auth.user, config)) {
      return auth ? apiError(reply, 403, 'admin_required', '需要管理员权限') : undefined;
    }
    const [plugins, jobs, reports] = await Promise.all([
      db.query(`
        SELECT p.plugin_id,p.source_url,p.status,p.admin_suspended,p.updated_at,
          p.publisher_subject_id,p.publisher_owner_id,p.publisher_owner_login,
          p.pending_owner_id,p.pending_owner_login,p.publisher_transfer_status,
          v.version,v.commit_sha,v.manifest_schema_version,v.compatibility_state
        FROM plugins p LEFT JOIN plugin_versions v ON v.id=p.latest_version_id
        ORDER BY p.updated_at DESC LIMIT 200
      `),
      db.query(`
        SELECT j.id,j.status,j.attempt,j.diagnostics_json,j.created_at,j.finished_at,s.source_url,s.plugin_id
        FROM validation_jobs j JOIN submissions s ON s.id=j.submission_id
        ORDER BY j.created_at DESC LIMIT 200
      `),
      db.query(`
        SELECT r.id,r.plugin_id,r.reason,r.details,r.status,r.created_at,u.login AS reporter
        FROM reports r JOIN users u ON u.id=r.reporter_user_id
        ORDER BY r.created_at DESC LIMIT 200
      `)
    ]);
    return { apiVersion: 2, plugins: plugins.rows, jobs: jobs.rows, reports: reports.rows };
  });
}
