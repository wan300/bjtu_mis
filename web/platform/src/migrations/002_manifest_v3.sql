ALTER TABLE plugins
  ADD COLUMN IF NOT EXISTS github_repository_id TEXT,
  ADD COLUMN IF NOT EXISTS publisher_subject_id TEXT,
  ADD COLUMN IF NOT EXISTS publisher_owner_id TEXT,
  ADD COLUMN IF NOT EXISTS publisher_owner_login TEXT,
  ADD COLUMN IF NOT EXISTS publisher_owner_type TEXT,
  ADD COLUMN IF NOT EXISTS pending_owner_id TEXT,
  ADD COLUMN IF NOT EXISTS pending_owner_login TEXT,
  ADD COLUMN IF NOT EXISTS pending_owner_type TEXT,
  ADD COLUMN IF NOT EXISTS publisher_transfer_status TEXT NOT NULL DEFAULT 'none';

ALTER TABLE plugin_versions
  ADD COLUMN IF NOT EXISTS manifest_schema_version INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS runtime_version INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS min_runtime_version INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS data_schema_version INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS compatibility_state TEXT NOT NULL DEFAULT 'legacy',
  ADD COLUMN IF NOT EXISTS verification_level TEXT NOT NULL DEFAULT 'automated';

UPDATE plugin_versions
SET manifest_schema_version = CASE
  WHEN (manifest_json->>'schema_version') ~ '^[0-9]+$'
    THEN (manifest_json->>'schema_version')::INTEGER
  ELSE 0
END
WHERE compatibility_state = 'legacy';

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'plugins_publisher_transfer_status_check'
  ) THEN
    ALTER TABLE plugins
      ADD CONSTRAINT plugins_publisher_transfer_status_check
      CHECK (publisher_transfer_status IN ('none', 'pending', 'approved', 'rejected'));
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'plugin_versions_compatibility_state_check'
  ) THEN
    ALTER TABLE plugin_versions
      ADD CONSTRAINT plugin_versions_compatibility_state_check
      CHECK (compatibility_state IN ('compatible', 'legacy'));
  END IF;
END
$$;

CREATE INDEX IF NOT EXISTS plugin_versions_v3_catalog_idx
  ON plugin_versions(published_at DESC, plugin_id)
  WHERE manifest_schema_version = 3 AND compatibility_state = 'compatible';

CREATE INDEX IF NOT EXISTS plugins_publisher_owner_idx
  ON plugins(publisher_owner_id)
  WHERE publisher_owner_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS plugins_github_repository_id_idx
  ON plugins(github_repository_id)
  WHERE github_repository_id IS NOT NULL;
