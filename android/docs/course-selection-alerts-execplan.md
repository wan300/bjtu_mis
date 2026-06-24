# ExecPlan: Course Selection Alerts and Captcha Priority

## 1. Purpose and user-visible outcome

- User scenario: when course selection is running in the background, the user should see one persistent low-noise notification, and should be alerted immediately when a captcha or successful grab needs attention.
- Current problem: the running foreground notification exists, but successful grabs do not send a dedicated notification, captcha alerts do not explicitly vibrate, and the captcha dialog does not auto-focus the input after returning from a notification.
- Completed behavior: running state remains visible in the notification shade without repeated alerting; new captcha challenges and true successful grabs send high-priority notifications with strong vibration; tapping returns to the course selection screen where the captcha field is ready for input.
- Success criteria: no full-screen intent, no new dependency, no upstream course-selection protocol change, and `already_selected` does not trigger a success alert.

## 2. Repository context

- Android project root: `android/`.
- Relevant code: `CourseSelectionRunner`, `CourseSelectionForegroundService`, `CourseSelectionScreen`, and `Models.kt`.
- Relevant tests: `android/app/src/test/kotlin/cn/edu/bjtu/mis/data/course/CourseSelectionRunnerTest.kt`.
- Existing permissions: `POST_NOTIFICATIONS`, `VIBRATE`, and foreground service permissions are already declared.
- Current worktree contains unrelated user changes; this task must not revert or rewrite them.

## 3. Constraints and non-goals

- Do not modify AA provider request flow, captcha submit protocol, Room schema, Open WebUI, or build dependencies.
- Do not use full-screen intents.
- Do not treat cached or pre-existing `already_selected` / `target_already_selected` statuses as a newly grabbed course.
- Keep running notification low importance and `setOnlyAlertOnce(true)`.
- Android notification channels, user notification settings, Do Not Disturb, and battery policies may still limit actual alert strength.

## 4. Proposed design

- Add a latest success alert event to `CourseSelectionRunState`; the event contains a monotonically increasing id, course identity, message, and optional replace rule id.
- Emit the event only for true `success` and `replace_success` statuses.
- In the foreground service, track last notified captcha id and success event id so each event alerts once.
- Build captcha and success alerts on the existing high-importance course-selection alerts channel and explicitly call vibrator APIs with a strong pattern.
- In Compose, create a focus requester for the captcha text field, show the keyboard when a challenge appears, and submit on IME Done.

## 5. Milestones

- Milestone 1: Runner event model and tests.
  - Observable behavior: success events appear for real grabs and not for `already_selected`.
  - Validation: `Set-Location android; .\gradlew.bat test --tests cn.edu.bjtu.mis.data.course.CourseSelectionRunnerTest`.
- Milestone 2: Notifications and UI priority.
  - Observable behavior: captcha and success events use high-priority notification plus vibration, and captcha input auto-focuses.
  - Validation: `Set-Location android; .\gradlew.bat test`.

## 6. Rollback plan

- Revert this document plus changes to `Models.kt`, `CourseSelectionRunner.kt`, `CourseSelectionForegroundService.kt`, `CourseSelectionScreen.kt`, and `CourseSelectionRunnerTest.kt`.
- No database, persisted data, dependency, or generated-asset rollback is required.

## 7. Progress log

- 2026-06-24: Created plan after confirming existing running notification behavior and gaps in success notification, explicit vibration, and captcha input focus.
- 2026-06-24: Implemented success alert events, high-priority captcha/success notification vibration, captcha input auto-focus, and runner unit test coverage.
- 2026-06-24: Target Gradle test attempts did not complete in this local environment before timeout and did not produce fresh XML results; user confirmed course-selection interface-related testing may be skipped for now.
