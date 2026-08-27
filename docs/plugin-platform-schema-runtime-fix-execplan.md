# ExecPlan: Fix plugin platform contract schema runtime loading and deploy it

## Purpose and user-visible outcome

The production validation worker rejects valid Manifest v3 submissions because its Docker image
does not contain every schema and registry file used by `tools/third-party-service-lint.cjs`.
After this change, the worker loads the generated contract inputs inside `/repo`, accepts known
contract_v1 capabilities and marketplace categories, and reports an internal platform
configuration failure without misleading plugin diagnostics if those inputs are ever missing.

Success means a production worker image contains the Manifest schema, marketplace schema, and
Capability Contract Registry; the platform tests and an image smoke test pass; a new immutable
release is deployed on `my-server`; and the production API and worker are healthy.

## Repository context and constraints

- Runtime image: `web/platform/Dockerfile`.
- Validator integration: `web/platform/src/validator.ts`.
- Shared linter and schema loader: `tools/third-party-service-lint.cjs`.
- Platform unit tests: `web/platform/test/unit/validator.test.ts`.
- Production Compose: `deploy/docker-compose.plugins.yml` with `REPOSITORY_ROOT=/repo`.
- Production root: `/opt/bjtu-plugin-platform/current`, pointing to an immutable timestamped
  directory under `/opt/bjtu-plugin-platform/releases/`.
- Existing uncommitted Android, contract registry, generated schema, SDK, and documentation edits
  belong to the user and must not be reverted or overwritten.
- Do not change Manifest v3, contract_v1, capability IDs, origin separation, bridge rules,
  publisher identity, storage isolation, or campus proxy security boundaries.
- Do not print or copy production secrets. Do not delete database or artifact volumes.
- No database migration is needed for this repair.

## Design

1. Copy every runtime input consumed by `loadManifestSchema` into the production image:
   `docs/third-party-service-manifest.schema.json`, `docs/bjtu-marketplace.schema.json`, and
   `plugin-tooling/contracts/capability-contracts.json`.
2. Make schema loading fail fast. The loader may record the single infrastructure diagnostic for
   CLI callers, but it must not return an empty registry with zero package limits. The platform
   validator must stop before plugin linting when schema loading fails.
3. Add a regression test using an intentionally incomplete repository root. It must prove that
   validation reports the missing generated contract input and does not append unknown capability,
   unknown category, zero file limit, zero byte limit, or icon-size false positives.
4. Build the production Docker image and inspect `/repo` from that image before deployment.

## Implementation and validation

1. Record `git status --short` and the server's active release/service state.
2. Update the Dockerfile, linter schema loading contract, validator handling, and unit tests.
3. Run from `web/platform`: `npm run typecheck`, `npm test`, `npm run test:integration`, and
   `npm run test:e2e`.
4. Run shared Manifest lint fixtures and `plugin-tooling` generation/type/test/package checks.
5. Run `docker build` using `web/platform/Dockerfile`, then assert all three `/repo` files exist and
   the linter loads non-zero package limits from inside the image.
6. Upload only the required repository files into a new timestamped server release. Verify hashes,
   atomically update `/opt/bjtu-plugin-platform/current`, rebuild Compose services, and retain the
   old release.
7. Verify migration exit status, PostgreSQL health, API/worker running state, localhost and public
   health endpoints, and worker logs. Requeue the rejected submission only if an existing supported
   platform operation can do so without modifying unrelated records; otherwise record that the user
   should resubmit.

## Rollback

Before switching, record the resolved target of `/opt/bjtu-plugin-platform/current`. If build,
startup, health, or validation checks fail, atomically point `current` back to that target and run
`docker compose -f deploy/docker-compose.plugins.yml up -d --build` from the restored release.
Keep the existing PostgreSQL and artifact volumes. Do not run `docker compose down -v`, delete any
release, or modify `/etc/bjtu-plugin-platform.env` and `/etc/bjtu-plugin-postgres-password`.

## Progress log

- 2026-08-13: Diagnosed the production rejection. The image lacks the marketplace schema and
  Capability Contract Registry; the schema loader then substitutes empty lists and zero limits,
  producing cascading false plugin diagnostics.
- 2026-08-13: Created this plan before production-affecting edits and deployment.
- 2026-08-13: Recorded the active production rollback target as
  `/opt/bjtu-plugin-platform/releases/20260811-035810`. PostgreSQL is healthy, API and worker are
  running, and the server has about 9 GiB free on the deployment filesystem.
- 2026-08-13: Implemented runtime schema copies, fail-fast schema loading, platform guard logic,
  a cascading-diagnostic regression test, and a CI production-image smoke test.
- 2026-08-13: Platform typecheck and 18 unit tests passed. Integration completed with its two
  PostgreSQL cases skipped because local `TEST_DATABASE_URL` is unset. All six e2e tests passed.
  All four shared Manifest lint fixtures passed.
- 2026-08-13: Tooling generation check, typecheck, template check, and deterministic package check
  passed. Tooling tests passed 34 of 35 cases; the remaining real-browser smoke test cannot execute
  Chrome or Edge in the restricted desktop environment and is unrelated to this backend repair.
- 2026-08-13: Local Docker image build could not start because Docker Desktop's Linux engine is not
  running. The required image build and `/repo` smoke test will run on the server's staged release
  before the production symlink is switched.
- 2026-08-13: The working tree contains an uncommitted Android automation contract extension.
  Deployment will keep the active server release as its base and supply the marketplace schema and
  Capability Registry from Git `HEAD`, preventing those unrelated capabilities from being deployed.
- 2026-08-13: Created `/opt/bjtu-plugin-platform/backups/20260813-105705/postgres.dump` and
  `artifacts.tar.gz`, both mode `0600`, before switching production.
- 2026-08-13: Built the production image on the server and passed the `/repo` smoke test. It loaded
  contract_v1, all five capabilities used by the target submission, the `other` category, and the
  expected limits of 1,000 files, 50 MiB extracted data, and a 1 MiB icon.
- 2026-08-13: Preflighted the current JMComic repository archive through the fixed image. Package
  validation succeeded with four files and an 80,247-byte canonical archive; only five non-blocking
  `http://www.w3.org` string-scan warnings remained.
- 2026-08-13: Atomically switched production from release `20260811-035810` to
  `/opt/bjtu-plugin-platform/releases/20260813-105705`. Migration exited 0, PostgreSQL remained
  healthy, and API and worker restarted successfully.
- 2026-08-13: Requeued only submission `bbc31e68-c471-4069-8a15-57120e8f14be` with strict state and
  attempt preconditions and audit record `77be0e4c-46f8-4431-9054-e9bc46bc4532`. The worker
  completed attempt 2 and published `io.github.wan300.jmcomic` version `2.0.2` at commit
  `cef5b685eb95c3bf2e8d9e590eece14fa8390f76`.
- 2026-08-13: Production localhost health, public `/api/v3/plugins`, session endpoint, icon, and
  artifact checks returned 200. The public icon is 648 bytes and the artifact is 80,247 bytes.
  Recent logs contain no contract-schema, unknown capability/category, zero-limit, or icon false
  positive diagnostics.

## Completion checklist

- [x] Server pre-deployment state and rollback target recorded.
- [x] Runtime image contains all contract inputs.
- [x] Schema load failure stops before plugin linting and has regression coverage.
- [x] Platform, tooling, lint, and Docker smoke checks pass or failures are documented.
- [x] New immutable server release is uploaded and verified.
- [x] Production services and health endpoints pass after deployment.
- [x] Final `git status --short` and scoped diff are reviewed; unrelated user changes remain intact.
