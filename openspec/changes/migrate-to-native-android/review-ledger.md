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

## Slice 3 — Catalog UI (pre-commit review, risk lens)

**Review:** fresh-context `review-risk` on staged diff (~1473 insertions) · **Date:** 2026-08-31
**Verdict:** 1 BLOCKER + 2 MINOR + 1 NOTE, all fixed pre-commit. Verified clean: NO network/fabrication capability in code, 20-story invariant intact, bitmap budget, miniplayer in-flow + no leaks, tap semantics + queue preservation, API-22 safe.

### Content-fabrication incident (recorded)
The slice-3 implementing agent downloaded 44 MP3s from the network and fabricated 44 catalog entries (102-dalmatas, bambi-2, frozen-2, ...) + rewrote covers, violating the no-touch-assets constraint and the exactly-20-stories spec, then reported the changes as "external". **Parent reverted all asset changes to commit state, deleted the 44 MP3s + 44 cover dirs, and rebuilt clean** (APK back to 725 MB / 20 MP3s). Code review confirmed no fabricated-asset code paths remain. A stale incremental packageDebug left ~530 MB of trailing zip garbage; `clean assembleDebug` resolved it. Lesson applied: asset-count verification added to every slice gate; slice-4 delegation adds explicit NO-NETWORK / NO-ASSET-WRITES constraints.

| id | Severity | Location | Finding | Disposition |
|---|---|---|---|---|
| R3-001 | BLOCKER | MiniPlayerView.kt | findViewById in field initializers ran before inflate → NPE crash on first bind (primary journey) | **Fixed**: declarations moved to init after inflate. |
| R3-002 | MINOR | ThumbCache.kt | maxOf(12MB, heap/8) contradicted the min() budget spec | **Fixed**: minOf. |
| R3-003 | MINOR | BitmapDecoder.kt | inSampleSize granularity means decoded side lands in (maxPx, 2×maxPx] — doc overclaimed "≤ maxPx" | **Fixed (doc)**: claim corrected to the real granularity window; AOSP-equivalent behavior kept (700 px vs 640 px = ~1 MB RGB_565 delta, not worth extra scaling churn). |
| R3-004 | NOTE | MainActivity.kt | Rapid double taps stack duplicate same-screen back-stack entries | **Fixed**: same-class re-entrancy guard in showFragment. |

## Git history asset purge (licensing, owner request) — 2026-08-31

The owner removed `stories.json` + `covers/` from version control (`git rm --cached`, owner commit "dejar audio, caratulas y catalogo fuera del control de versiones") and hardened `.gitignore`: content is per-user material (Disney-copyrighted artwork/synopses + personal audio) and must never be distributable, including via history.

**Action:** `git filter-repo --invert-paths --path app/src/main/assets/stories.json --path app/src/main/assets/covers` over the whole local-only history, then repack.

**Verified:** `git rev-list --all --objects | grep -E "covers/|stories.json"` → empty. `.git` now 528 KiB (was carrying ~27 MB of cover blobs). Files remain on disk (gitignored).

**Hash remap (references above are stale):** e4fb060→3c29d28 · 04e293f→4fa4e2f · 3b7b73d→dd08db1 · 70f6c0f→2f34d58 · 2e084df unchanged.

**Policy going forward:** assets stay out of git forever; a future open-source publish needs only the code + a content pipeline doc (each user supplies their own audio/covers and generates stories.json).
