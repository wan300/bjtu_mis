# ExecPlan: Homepage Priority Updates and Room Migration

## Purpose and user-visible outcome

The Android home screen should show a priority reminder card directly below the hero. The card highlights near-due homework first, then newly added or modified exam, score, course-resource, and homework records detected by comparing the latest successful fetch with the previous successful fetch.

Success means users can open the app and immediately see the top five actionable learning updates, while existing quick sync remains lightweight.

## Repository context

- Android project root: `android/`.
- Home screen: `app/src/main/java/cn/edu/bjtu/mis/ui/screens/OverviewScreen.kt`.
- Sync and cached snapshots: `app/src/main/java/cn/edu/bjtu/mis/data/repository/Repositories.kt`.
- Room database and migrations: `app/src/main/java/cn/edu/bjtu/mis/data/db/AppDatabase.kt`.
- Existing migration checks: `app/src/test/kotlin/cn/edu/bjtu/mis/data/db/AppDatabaseMigrationTest.kt`.

## Constraints and non-goals

- Do not add network work to homepage quick sync beyond the current calendar, exams, and homework fetches.
- Do not include `history_scores`; score updates mean `scores` only.
- Do not recurse into course-resource subfolders; full sync checks root resources for every current-term course.
- Do not add read, dismiss, notification, or detail-deep-link behavior.
- Do not direct the UI to call providers; comparison and persistence stay in repository/database layers.

## Proposed design

- Add a Room table for latest module change summaries and migrate database version 9 to 10.
- Before replacing a module snapshot, read the old payload, compute user-visible added/modified records, save the change summary, then save the new snapshot.
- Treat the first successful fetch with no old snapshot as baseline only and save an empty summary.
- Build overview highlights from cached homework plus persisted update summaries:
  - near-due homework within 72 hours, urgent within 24 hours;
  - exam updates;
  - score updates;
  - course-resource updates;
  - non-near-due homework updates.
- De-duplicate homework that appears as both near-due and updated, showing the near-due row with an update marker.

## Room impact and rollback

- Database version increases from 9 to 10.
- New table stores only derived update summaries, so rollback can drop the table and remove migration 9->10 without losing primary user data.
- If migration fails in validation, revert the database version, migration, DAO additions, change summary model, sync comparison code, overview UI changes, and related tests.

## Validation plan

- Add pure Kotlin unit tests for change detection and overview highlight ordering.
- Extend DAO fake/repository tests to assert summary replacement on snapshot saves.
- Extend migration tests to assert the new table SQL shape.
- Run `Set-Location android; .\gradlew.bat test`.
- Run `Set-Location android; .\gradlew.bat assembleDebug` only if Android packaging/resources are affected.

## Progress log

- 2026-06-23: Plan created before implementing Room migration and homepage priority updates.
- 2026-06-23: Added Room version 10 migration and `module_update_summaries` persistence.
- 2026-06-23: Added snapshot comparison for homework, exams, scores, and all-course root course resources.
- 2026-06-23: Added home-screen priority reminder card below the hero.
- 2026-06-23: Added unit tests for update detection, highlight ordering, summary replacement, and migration SQL shape.
- 2026-06-23: Validation passed with `Set-Location android; .\gradlew.bat --no-daemon test`.
