# Review Findings Ledger — migrate-to-native-android

## Slice 1 — Scaffold + spike (pre-commit review, risk lens)

**Review:** fresh-context `review-risk` on staged diff · **Date:** 2026-08-31
**Verdict:** clean — no BLOCKER/MAJOR. Verified: manifest permissions exact + no INTERNET, build pins match design D9, port script fails loudly, no secrets/network, audio binaries correctly gitignored.

| id | Severity | Location | Finding | Disposition |
|---|---|---|---|---|
| R1-001 | WARNING | tools/port-catalog.py | Hardcoded default `--content-dir` (machine-specific) | Resolved-by-design: arg is overridable; single-machine project per proposal. No change. |
| R1-002 | WARNING | apply-progress.md / port-catalog.py | Overclaim: fixes "asserted" but no assertion in code | **Fixed post-review**: assertions added to `validate()` (bambi prefix, Los Increíbles accent); script re-run green; wording corrected in apply-progress.md. |
| R1-003 | SUGGESTION | .gitignore | Comment referenced non-existent tools/download-gradle.sh | **Fixed post-review**: comment now points at gradle/wrapper/gradle-wrapper.properties. |

Full review text preserved in the session record; contract: findings were reviewed before commit 1 per delivery decision (git local, commit-per-slice, fresh review before each commit).

## Slice 2 — Catalog data + playback core (pre-commit review, risk lens)

**Review:** fresh-context `review-risk` on staged diff (~1330 insertions, Kotlin playback core) · **Date:** 2026-08-31
**Verdict:** 1 BLOCKER + 3 MAJOR + 1 MINOR + 1 NOTE found before commit; all fixed and build re-verified green.

| id | Severity | Location | Finding | Disposition |
|---|---|---|---|---|
| R2-001 | BLOCKER | PlaybackService/MediaNotificationProvider | `Bundle.EMPTY` is API 23+ → NoSuchFieldError crash on API 22 (onConnect + notification) | **Fixed**: `Bundle()` in all 6 sites. |
| R2-002 | MAJOR | PlaybackRepository progress runnable | Runnable stopped rescheduling but never cleared its slot → all future progress ticks dead (frozen seek/labels in slice 4) | **Fixed**: slot cleared when `!shouldKeepTicking()`. |
| R2-003 | MAJOR | PlaybackRepository error path | Stale same-id error events could label a healthy reload as failed; loadGen never gated listener events | **Fixed**: error surfaced from `c.playerError` (current truth at main-thread dispatch, cleared by next prepare) + load-gen guard on async resolve callbacks; rationale documented in code. |
| R2-004 | MAJOR | AudioSourceResolver | COPY_TO_FILES fallback did blocking ~60 MB copy on caller thread → ANR | **Fixed**: new `resolveAsync()` — copy on worker thread, callback on main looper, `performLoad` consumes it with generation guard. |
| R2-005 | MINOR | PlaybackRepository connect | `connectFailures` never reset; unexpected service death left UI disconnected until restart | **Fixed**: counter reset on ready; dedicated `sessionListener` (MediaController.Builder.setListener — note: MediaController.Listener does NOT extend Player.Listener in media3 1.2.1, verified via javap against the AAR) handles onDisconnected → bounded reconnect. |
| R2-006 | NOTE | PlaybackRepository seekTo | Clamp to Long.MAX_VALUE while duration unknown → pre-READY ±15s could hit end clamp → premature Ended | **Fixed**: catalog duration fallback; seek ignored while no usable duration. |

Verified clean: player build per D8 (focus/wake/becoming-noisy), manifest declarations, pre-O channel guard, artwork decode ≤256 px RGB_565, ActionFactory-owned PendingIntents, no desugaring-dependent APIs (imports audited), validator-first catalog parsing, 7-state machine reachable, end-of-queue stop parity.
