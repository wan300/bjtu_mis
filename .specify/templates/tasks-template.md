# Tasks: [FEATURE NAME]

**Input**: spec and plan for [FEATURE NAME]  
**Constitution Version**: 1.0.0

## Task Rules

- Each task MUST name the file, component or behavior it changes.
- Tasks that touch parser, provider, repository, Agent tools or Open WebUI local-first behavior MUST include matching tests or fixtures.
- Tasks that touch credentials, Cookie, mail, coursework actions, files, archives or generated outputs MUST include explicit safety checks.
- High-risk changes MUST reference the active ExecPlan and include rollback verification tasks.

## Phase 1: Setup and Scope

- [ ] T001 Check `git status --short` and record unrelated user changes.
- [ ] T002 Confirm the spec, non-goals and unresolved `TODO: confirm` items.
- [ ] T003 Identify existing project patterns, helpers and tests to reuse.

## Phase 2: Tests and Safety First

- [ ] T004 Add or update parser fixtures, provider fakes, repository tests, Agent tool boundary tests or Vitest coverage required by the plan.
- [ ] T005 Add safety coverage for user confirmation, workspace boundaries, path traversal, file size limits, privacy-sensitive logs or failure feedback where relevant.

## Phase 3: Implementation

- [ ] T006 Implement scoped Android, Open WebUI, data, sync or bridge changes using existing architecture boundaries.
- [ ] T007 Update persistence, cache, migration or local-first behavior only as described in the plan.
- [ ] T008 Update user-facing states for loading, empty, error, offline and permission failures where relevant.

## Phase 4: Documentation and Validation

- [ ] T009 Update README, AGENTS, PLANS or feature docs affected by behavior, commands or boundaries.
- [ ] T010 Run Android validation selected by scope: `Set-Location android; .\gradlew.bat test` or `Set-Location android; .\gradlew.bat assembleDebug`.
- [ ] T011 Run Open WebUI validation selected by scope: `Set-Location android\open-webui; npm run test:frontend -- --run` or `npm run check`.
- [ ] T012 Run `git diff --check`, inspect `git diff`, and confirm `git status --short` contains only intended files.

## Completion Notes

- Validation results:
- Known baseline failures:
- Remaining TODOs:
- Rollback notes:
