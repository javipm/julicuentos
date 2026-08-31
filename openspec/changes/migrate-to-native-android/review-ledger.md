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

## Slice 4 — Player UI (pre-commit review, risk lens)

**Review:** fresh-context `review-risk` on staged diff (surviving artifacts + recovered tracked files) · **Date:** 2026-08-31
**Verdict:** 0 BLOCKER / 0 MAJOR; 1 MINOR + 3 NOTE, all fixed pre-commit. Verified clean: seek contract core (zero player calls during drag, single commit, tap path), all 7 states bound, listener removal, CoverHaloView single onDraw + thresholds, dimens/ids coherence both orientations, qualifier precedence h720dp-land > land, no security findings, assets untouched.

**Recovery note:** before this review, a `git filter-repo` checkout reset tracked-modified files (dimens/strings/PlayerFragment/StoryAdapter/versionCode/SDD artifacts). Untracked artifacts survived. A recovery writer rebuilt the tracked changes against the surviving contracts; parent should have committed or stashed before the history rewrite (process lesson recorded).

| id | Severity | Location | Finding | Disposition |
|---|---|---|---|---|
| R4-001 | MINOR | SeekBarController.kt | dragging cleared before commit → stale pre-seek tick could snap thumb backwards (pain point #2 regression risk) | **Fixed**: pendingTargetMs hold until player position within ±1.5 s / crosses target; label pinned to target meanwhile. |
| R4-002 | NOTE | SeekBarController.kt | metadata tick mid-drag mixed new duration with old max → preview drift | **Fixed**: seekBar.max updated regardless of dragging. |
| R4-003 | NOTE | TimeFormat.kt | ceil via (v+999)/1000 overflows at Long.MAX_VALUE, violating KDoc | **Fixed**: overflow-safe ceil (v/1000 + carry). |
| R4-004 | NOTE | PlayerFragment.kt | Error card showed English repository/library messages | **Fixed**: mapped to Spanish copy (error_audio_faltante / error_generico), diagnostic kept in logcat. |

## Slice 5 — Queue + Timer + Persistence (pre-commit review, risk lens)

**Review:** fresh-context `review-risk` on staged diff (~2600 insertions, two-agent seam: persistence/timer core + UI completion) · **Date:** 2026-08-31
**Verdict:** 1 CRITICAL + 1 WARNING + 1 SUGGESTION, all resolved pre-commit. Verified clean: timer expiry + 10×1s fade + cancel rules (incl. notification/focus-loss pause path), endOfStory suppression REAL in onEnded, persistence guards (hydration gate, user-wins discard, tolerant parser, D3 elapsedRealtime anchor + validity window), queue semantics (de-dup, clamped moves, no-wrap auto-advance, circular ⏭, play-now preserves queue), single source of truth across the two-agent seam, 72dp rows / 48dp visuals in 52dp targets, flat design, 100% Spanish strings, versionCode 5, 61 unit tests behavior-asserting.

| id | Severity | Location | Finding | Disposition |
|---|---|---|---|---|
| R5-001 | CRITICAL | MainActivity.kt | No Activity.onStop flush — spec "Flush cadence (5 s + onStop)" + screen-off acceptance violated; last <5 s of mutations unprotected against memory-pressure kill | **Fixed**: onStop → repository.flushNow() (not on isFinishing). |
| R5-002 | WARNING | PlaybackRepository.hydrate | User mutations issued during connect set userMutated → markDirty early-returned pre-hydration → hydrate skipped snapshot AND left dirty unarmed → user's fresh state silently unpersisted on force-stop | **Fixed**: after the user-wins discard, re-arm markDirty() so the next tick/onStop flush persists the user's state. |
| R5-003 | SUGGESTION | QueueAdapter.submit | notifyDataSetChanged fine at current scale (mutation-driven only); ListAdapter/DiffUtil if rows grow | Noted, no change (per-slice rule: mutation-driven updates, no progress rebinds). |

**Process notes:** slice-5 first agent crashed mid-run (build left broken: stubs referenced removed placeholder strings); recovery agent finished UI + docs against the surviving core. Asset state (64 stories) is owner-managed content, untouched by agents.

## Design polish pass — Opus consult implemented (post-verify) · 2026-08-31

Owner feedback: player "regulinchi", catalog needed gaps + whole covers. Owner requested a Claude-Opus design consult, delivered via Orca orchestration (worker `ctx_ba4c889c7b6d`, model opus/high) → `design-consult-opus.md` (12 prioritized deltas). Implemented by a writer agent (39 files, C1–C12): panel-based player split with 8/16/24 ladder + labeled Spanish chips, 16:9 whole covers on matte (rect-aware CoverHaloView), catalog 4 columns with gaps + mint "Sonando" card frame + overflow on cover, floating miniplayer card, queue index circles + duration lines, left-aligned timer rows with check.

**Device evidence (Fire HD 10, KFSUWI API 22, adb):** installed v6, playback state=3 speed=1.0, seek verified by owner, wifi OFF/ON toggles → process alive, 0 crashes; screenshots of catalog + player confirm the consult landed.

**Review (fresh review-risk lens) fixed pre-commit:**

| id | Severity | Location | Finding | Disposition |
|---|---|---|---|---|
| R6-001 | BLOCKER | PlayerFragment.kt | timerChip/Icon/Label lateinit never assigned → UninitializedPropertyAccessException on every player open | **Fixed**: findViewById trio in onViewCreated. |
| R6-002 | MAJOR | AndroidManifest.xml | ACCESS_NETWORK_STATE strip (R-1) could SecurityException in media3 DefaultBandwidthMeter on connectivity change | **Settled with device evidence**: wifi OFF/ON toggles with active playback → process alive, playback continued, 0 SecurityExceptions. Strip retained; DV soak still pending. |
| R6-003 | NOTE | CoverHaloView.kt | Peach timer ring radius used base-ring inset (17 vs 18 dp) | **Fixed**: radius from own inset. |
| R6-004 | NOTE | values-w1024dp/integers.xml | Stale "5 columns" comment | **Fixed**: comment updated to 4. |
