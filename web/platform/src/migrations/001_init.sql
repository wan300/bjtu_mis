CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  github_id TEXT NOT NULL UNIQUE,
  login TEXT NOT NULL,
  avatar_url TEXT NOT NULL DEFAULT '',
  encrypted_oauth_token TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,
  user_id TEXT REFERENCES users(id) ON DELETE CASCADE,
  oauth_state TEXT,
  csrf_token TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS sessions_expires_at_idx ON sessions(expires_at);

CREATE TABLE IF NOT EXISTS submissions (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  source_url TEXT NOT NULL,
  repo_owner TEXT NOT NULL,
  repo_name TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('queued', 'validating', 'published', 'rejected')),
  plugin_id TEXT,
  commit_sha TEXT,
  error_text TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS submissions_user_created_idx ON submissions(user_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS submissions_active_repository_idx
  ON submissions(lower(repo_owner), lower(repo_name))
  WHERE status IN ('queued', 'validating');

CREATE TABLE IF NOT EXISTS plugins (
  plugin_id TEXT PRIMARY KEY,
  submitter_user_id TEXT NOT NULL REFERENCES users(id),
  source_url TEXT NOT NULL,
  repo_owner TEXT NOT NULL,
  repo_name TEXT NOT NULL,
  default_branch TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('published', 'withdrawn', 'unlisted')),
  admin_suspended BOOLEAN NOT NULL DEFAULT false,
  latest_version_id TEXT,
  source_etag TEXT,
  source_failure_count INTEGER NOT NULL DEFAULT 0,
  last_checked_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(repo_owner, repo_name)
);

CREATE TABLE IF NOT EXISTS plugin_versions (
  id TEXT PRIMARY KEY,
  plugin_id TEXT NOT NULL REFERENCES plugins(plugin_id) ON DELETE CASCADE,
  version TEXT NOT NULL,
  commit_sha TEXT NOT NULL,
  manifest_json JSONB NOT NULL,
  warnings_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  archive_sha256 TEXT NOT NULL,
  package_digest_sha256 TEXT NOT NULL,
  package_bytes BIGINT NOT NULL,
  package_file_count INTEGER NOT NULL,
  artifact_path TEXT NOT NULL,
  icon_path TEXT NOT NULL,
  published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(plugin_id, commit_sha),
  UNIQUE(plugin_id, version)
);
CREATE INDEX IF NOT EXISTS plugin_versions_plugin_published_idx ON plugin_versions(plugin_id, published_at DESC);

CREATE TABLE IF NOT EXISTS validation_jobs (
  id TEXT PRIMARY KEY,
  submission_id TEXT NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
  status TEXT NOT NULL CHECK (status IN ('queued', 'running', 'completed', 'failed')),
  attempt INTEGER NOT NULL DEFAULT 0,
  diagnostics_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS validation_jobs_active_submission_idx ON validation_jobs(submission_id) WHERE status IN ('queued', 'running');

CREATE TABLE IF NOT EXISTS reports (
  id TEXT PRIMARY KEY,
  plugin_id TEXT NOT NULL REFERENCES plugins(plugin_id) ON DELETE CASCADE,
  reporter_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  reason TEXT NOT NULL,
  details TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved', 'dismissed')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  resolved_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS audit_log (
  id TEXT PRIMARY KEY,
  actor_user_id TEXT REFERENCES users(id) ON DELETE SET NULL,
  action TEXT NOT NULL,
  plugin_id TEXT,
  metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
