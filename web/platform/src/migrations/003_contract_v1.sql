ALTER TABLE submissions
  ADD COLUMN IF NOT EXISTS contract_profile TEXT NOT NULL DEFAULT 'legacy';

ALTER TABLE plugin_versions
  ADD COLUMN IF NOT EXISTS contract_profile TEXT NOT NULL DEFAULT 'legacy',
  ADD COLUMN IF NOT EXISTS runtime_floor INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS capabilities_json JSONB NOT NULL DEFAULT '{"required":[],"optional":[]}'::jsonb,
  ADD COLUMN IF NOT EXISTS marketplace_json JSONB,
  ADD COLUMN IF NOT EXISTS manifest_file_name TEXT NOT NULL DEFAULT 'bjtu-service.json';

UPDATE plugin_versions
SET contract_profile = CASE
      WHEN manifest_schema_version = 3
        AND manifest_json ? 'required_capabilities'
        AND manifest_json ? 'bridge_origins'
        THEN 'legacy_v3_p0a'
      ELSE 'legacy'
    END,
    runtime_floor = GREATEST(min_runtime_version, 0),
    capabilities_json = jsonb_build_object(
      'required', COALESCE(manifest_json->'required_capabilities', '[]'::jsonb),
      'optional', COALESCE(manifest_json->'optional_capabilities', '[]'::jsonb)
    ),
    marketplace_json = manifest_json->'marketplace',
    manifest_file_name = 'bjtu-service.json'
WHERE contract_profile = 'legacy';

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'submissions_contract_profile_check'
  ) THEN
    ALTER TABLE submissions
      ADD CONSTRAINT submissions_contract_profile_check
      CHECK (contract_profile IN ('legacy', 'contract_v1'));
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'plugin_versions_contract_profile_check'
  ) THEN
    ALTER TABLE plugin_versions
      ADD CONSTRAINT plugin_versions_contract_profile_check
      CHECK (contract_profile IN ('legacy', 'legacy_v3_p0a', 'contract_v1'));
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'plugin_versions_runtime_floor_check'
  ) THEN
    ALTER TABLE plugin_versions
      ADD CONSTRAINT plugin_versions_runtime_floor_check
      CHECK (runtime_floor >= 0);
  END IF;
END
$$;

CREATE INDEX IF NOT EXISTS plugin_versions_contract_v1_catalog_idx
  ON plugin_versions(published_at DESC, plugin_id)
  WHERE contract_profile = 'contract_v1'
    AND manifest_schema_version = 3
    AND compatibility_state = 'compatible';

CREATE INDEX IF NOT EXISTS submissions_contract_profile_idx
  ON submissions(contract_profile, created_at DESC);
