# ExecPlan: Course Replay Dedicated Native Player Refactor

## 1. Purpose and user-visible outcome

Course replay playback should move from a fragile embedded fullscreen Compose dialog to a dedicated native player page inspired by moneytoo/Player. Users should still be able to preview a replay inside the course page, then open a landscape immersive player with stronger controls and gestures.

Success criteria:
- The course replay screen still loads lessons and starts a basic embedded preview.
- Opening the full player launches a non-exported landscape Activity owned by this app.
- The full player supports play/pause, seek, stream switching, speed, volume, fit/crop/zoom, double-tap seek, horizontal seek, brightness/volume swipes, pinch zoom, PiP, MediaSession, audio focus, headset-disconnect pause, and keep-screen-on.
- Playback state is handed back to the embedded preview for the current session: position, play state, stream, speed, volume, and resize mode.
- Course replay listen reporting remains active every 60 seconds during playback.

## 2. Repository context

- Android app root: `android/`
- Course screen: `android/app/src/main/java/cn/edu/bjtu/mis/ui/screens/CourseReplayScreen.kt`
- New native player package: `android/app/src/main/java/cn/edu/bjtu/mis/ui/player/`
- Player view layout: `android/app/src/main/res/layout/course_replay_player_view.xml`
- Existing playback data: `CourseReplayPlaybackInfo` contains HLS stream choices, referer, course IDs, user IDs, and replay metadata.
- Existing dependencies already include Media3 ExoPlayer, HLS, UI, and OkHttp datasource. This refactor may add only same-version `media3-session`.

## 3. Constraints and non-goals

- Do not import moneytoo/Player as a full app or copy its file chooser, external Intent filters, subtitle scanning, delete/next-file actions, TV mode, or settings screen.
- Do not add `WRITE_SETTINGS`, exported playback entry points, local storage browsing, or general-purpose external video playback.
- Do not change parser/provider schemas unless strictly required.
- Keep Media3 at the current project version.
- Do not write credentials, cookies, replay URLs, or personal course data into logs, fixtures, or docs.
- Protect existing dirty worktree changes and do not revert unrelated files.

## 4. Proposed design

- Add `CourseReplayPlayerContract` with serializable launch and result payloads using `AppJson`.
- Add `CourseReplayPlayerActivity` as a non-exported Activity. It obtains `CourseReplayRepository` and OkHttp through `BjtuMisApplication.container`.
- Add a reusable Kotlin/Compose native player surface used in both preview and dedicated modes.
- Keep embedded preview basic: playback surface, progress, stream/speed/volume controls, and an "Open player" action.
- Move full-player-only behavior to the dedicated Activity: landscape immersive window, gestures, PiP, MediaSession, audio focus, headset disconnect pause, and screen-on behavior.
- Preserve listen reporting through the repository, not direct provider access.

## 5. Validation plan

- Run `Set-Location android; .\gradlew.bat test`.
- Run `Set-Location android; .\gradlew.bat assembleDebug`.
- Required real-device QA on a logged-in device:
  - open course replay and start embedded preview;
  - open the dedicated player and verify landscape immersive playback;
  - verify controls, gestures, stream switching, speed, Back return, PiP, MediaSession, audio focus, headset-disconnect pause, and keep-screen-on;
  - verify listen reporting keeps working without logging sensitive data.

## 6. Risks and rollback

- Risk: adding a second Activity creates lifecycle handoff bugs. Mitigation: typed launch/result payloads and session-only state handoff.
- Risk: MediaSession/PiP behavior varies by Android version. Mitigation: guard APIs by SDK and keep playback usable without PiP.
- Risk: true device QA may be blocked by campus login/session availability. Mitigation: record blocker; do not fake completion.
- Rollback: remove the new `ui.player` package, Activity manifest entry, media3-session dependency, and revert the course screen handoff changes. No database rollback is required.

## 7. Progress log

- 2026-06-24: Replaced the previous fullscreen-fix ExecPlan with the dedicated native player refactor plan.
