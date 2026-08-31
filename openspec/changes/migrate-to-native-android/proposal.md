# Proposal — migrate-to-native-android

**Change:** `migrate-to-native-android` · **Phase:** propose · **Date:** 2026-08-30
**Evidence base:** `openspec/changes/migrate-to-native-android/explore.md` (complete read-only exploration of the Expo/RN app at `~/julicuentos-rn`).
**Product decisions:** locked by the owner (see § Locked decisions). This document does not re-open them.

---

## Why — problem statement

`Julicuentos` is a bedtime audiocuento app for one child on one device: an Amazon Fire HD 10 (2015, Fire OS 5.3, **Android 5.1.1 / API 22**), sideloaded over adb, never published. Today it is an Expo SDK 49 / React Native 0.72 / `expo-av` app, and it fails at the one thing it must not fail at.

1. **Playback dies after the screen turns off.** Exploration found a concrete, verified cause, not a mystery: `src/lib/backgroundAudio.ts` calls `NativeModules.JulicuentosBackgroundAudio.acquire()`, and **that module does not exist anywhere in the repo** (no `android/`, no Expo module, no config plugin). Every acquire/release is a silent no-op. Nothing keeps the process alive except `staysActiveInBackground: true` — there is no foreground service, no owned `MediaSession`, no wake lock, no notification, no media-button handling. On Fire OS 5.3's aggressive process caching, the story stops exactly when the child falls asleep.
2. **The seek bar is broken by construction.** Every touch event fires a real seek (`setPositionAsync`), and the next status callback (250–500 ms later on this hardware) reports a stale position, so the thumb snaps backwards under the finger. Worse, a mid-drag stall triggers a **full audio reload per move event**, and the enclosing `ScrollView` can steal the gesture with no terminate handler. This is the second named pain point.
3. **100 % remote streaming is the wrong architecture for a bedroom app.** All 20 MP3s (~650 MB) stream from `ivoox.com` with no disk cache. ~350 lines of defensive code exist only to paper over that: a 3 s stall watchdog, a 12 s timeout, a 3-attempt counter, a 300 ms backoff, a "+2 s to hop the corrupt segment" heuristic, an error card with "Reintentar", and CORS/web branches. Network dependency is the root of a large share of the observed unreliability.
4. **The UI reads as dated and is held together by API-22 workarounds** — no elevation anywhere, 0 ms image fades, 500 ms progress ticks, an external progress store plus a three-way context split plus memoized cell comparators plus `FlatList` window tuning, a miniplayer demoted from overlay to in-flow footer, `NativeWind` classNames that "solo falla en API 22". Each is a correct patch for a React-reconciliation cost that a native app does not pay.
5. **The RN toolchain overhead is not justified for a single-device personal app.** Working on it means npm `pre*` hooks that regenerate a **gitignored** `catalog.ts`, a Python content downloader, EAS/CNG for native config, and an `expo-av` version ceiling — to ship one APK to one tablet for one family.

The opportunity: rewrite natively, bundle the audio, and both named bugs stop being bugs (they are consequences of the platform choice), while the visual identity gets a real refresh instead of another workaround.

## What Changes — proposed outcome

A **native Android app** — Kotlin + XML views (no Compose), `minSdk 22` / `compileSdk 34`, package `com.julicuentos.app`, delivered as a **single debug-signed APK** installed with `adb install -r` on the Fire HD 10. It replaces the Expo app entirely as the thing that runs on the device.

**Fixed (the two real bugs).**
- Background playback is owned properly: Media3 `ExoPlayer` inside a `MediaSessionService`, foreground service with `mediaPlayback` type, wake lock, `MediaStyle` notification with play/pause, ±15 s and next, plus artwork. Screen-off playback becomes an explicitly device-verified requirement.
- Seeking becomes **drag-preview + commit-on-release**: never seek while dragging, live target-time preview while the thumb moves, exactly one `seekTo()` on release. With local audio, a seek is instant, so the thumb lands where it was let go.
- The sleep timer survives screen-off (`SystemClock.elapsedRealtime`, not a JS interval) and ends with a ~10 s volume fade into pause instead of a hard cut.

**Deleted.**
- The RN/Expo app as the runtime; the phantom background-audio module; the whole streaming-recovery layer; the remote error/"Reintentar" card; CORS/web branches; every re-render workaround (progress store, context splits, card memoization, list window tuning). Error handling collapses to "file missing/unreadable".
- The empty-catalog screen state — the catalog is compiled in.
- The `INTERNET` permission. The app cannot make a network request.

**Improved.**
- All 20 stories (~650 MB) bundled in `app/src/main/assets/audio/<id>.mp3` (already downloaded): offline by construction, no buffering, no CDN.
- A "Noche de cuentos" UI refresh on the **same** palette (`#17152E / #28244B / #72E0B8 / #FFB66E / #F8F7FF / #AAA3CE`) and the same three bundled faces (Fredoka SemiBold, Nunito Sans Regular/Bold), still deliberately **flat** — no elevation, shadows or blur (a hardware constraint, not a style preference). Polish comes from typography, spacing, larger covers and micro-details; cards gain a **duration chip**; the grid adapts to **5 / 3 / 2 columns**; the player is genuinely dual-orientation with a split landscape layout; Spanish copy is kept at the same register and polished where it is cheap (including the misleading queue empty-state text).
- Content clean-ups ride along: the truncated `bambi` synopsis, the missing accents in "Los Increibles", and dropping the never-displayed `fechaPublicacion` field.

**Deliberate behavior changes vs the RN app.**
- Tapping a catalog cover ("Reproducir ahora") **no longer clears the queue** — a child can no longer destroy a built-up playlist with one tap.
- Timer expiry **fades** instead of pausing instantly; the queue is still preserved on expiry.

**Everything else stays at parity** — queue semantics (append with de-duplication, up/down reorder, remove, clear), `⏭` wraps the catalog circularly while **auto-advance at end of story stops when the queue is empty**, cold start restores the last story **paused at the last position** (never autoplaying), single global resume position, ±15 s transport, 52 dp minimum touch targets, `m:ss` / `h:mm:ss` time formatting.

## Scope

### In scope (deliverables)

| # | Deliverable | Outcome |
|---|---|---|
| 1 | Gradle project scaffold | Wrapper-pinned AGP/Kotlin/JDK, `app/` module, `minSdk 22` / `compileSdk 34`, package `com.julicuentos.app`, `noCompress "mp3"`, debug signing, permissions (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK`, `POST_NOTIFICATIONS`) and **no `INTERNET`**. `./gradlew assembleDebug` is green from the first slice. |
| 2 | Catalog data + media assets | 20 stories with `id / título / descripción / duración / thumbnail / cover` shipped as a Kotlin constant list or a `stories.json` asset (validator-first, unknown ids dropped), plus grid thumbnails (512 px) and player covers ported from `content/<id>/`, and the 20 MP3s already in `assets/audio/`. |
| 3 | Playback engine + service | ExoPlayer/MediaSessionService with an explicit state machine (`Idle/Loading/Ready/Playing/Paused/Ended/Error`), queue-aware auto-advance, circular manual next, ±15 s, MediaStyle notification, media-button handling, wake lock, error → message + retry. |
| 4 | Catalog UI | Adaptive grid, header, card with cover + title + 2-line synopsis + duration chip + "Sonando" pill, `⋮` bottom sheet (play now / add to queue / cancel), in-flow miniplayer with non-interactive progress strip, row-tap semantics (playing → open, paused → resume and open, other → play and open without waiting for load). |
| 5 | Player UI | Stack (portrait) and split (landscape) layouts via `values-*` resources, cover with square progress ring + peach timer ring, title/description/timer line, seek bar with drag-preview + commit-on-release and live time labels, transport row, "Ver cola". |
| 6 | Queue UI | Ordered list with cover, title, move up/down, remove, "Vaciar", empty state with corrected copy. |
| 7 | Sleep timer UI + logic | 15 / 30 / 45 min + "Al terminar este cuento" + "Desactivar"; `elapsedRealtime`-based countdown; ~10 s fade to pause; queue preserved; `end_of_story` suppresses auto-advance; 1 Hz countdown display that survives screen-off. |
| 8 | Persistence | One key, same schema shape (`currentStoryId`, `positionMs`, `queueIds`, `timer`, `updatedAt`), tolerant parser with safe defaults, ~5 s flush + flush on `onStop`, never before hydration and never overwriting a user action taken during restore. |
| 9 | Icon, splash, theme | Adaptive-safe launcher icon (API 22 fallback), dark `#17152E` launch screen, font families and `TextAppearance` ladder, flat color/dimension resources — no screen uses the system font. |
| 10 | Install & verification | Documented build + `adb install -r` flow (with `--no-local` fallback), versionCode monotonic, and a device checklist covering the five success criteria below. |

Work lands as incremental, individually buildable slices; no slice leaves the tree unbuildable.

### Out of scope (explicit non-goals → future changes)

- Kid-lock / kiosk mode, hiding system bars, blocking exit.
- Repeat-one / repeat-all (loop), shuffle, playback speed, chapters.
- Favorites, "continue listening" row, per-story resume position, listening history/stats.
- Any online feature: downloads, catalog refresh, new stories, ivoox URLs (kept as provenance metadata only), casting, updates.
- **New stories** — the catalog is the existing 20.
- Play Store / release signing / App Bundle / split ABIs; iOS; other devices or Android versions beyond "does not crash on API 22+"; tablets other than this Fire.
- Importing the RN app's stored queue/position (the old build is uninstalled; there is nothing worth migrating).
- Compose, Navigation-Compose, Hilt/Koin, DataStore-as-a-requirement, coroutines-as-a-requirement (design may use stdlib threads/Handler where simpler), TalkBack redesign, i18n beyond Spanish, unit/UI test infrastructure beyond a small core of pure logic.
- Changing the visual identity (new palette, new fonts, elevation, blur, animated vectors).

## Locked decisions (input to specs, not up for debate)

1. Native rewrite: Kotlin + XML views, no Compose, `minSdk 22`, `compileSdk 34`, one debug-signed APK via adb on the Fire HD 10 (2015, API 22). Personal family app, never published.
2. All 20 MP3s (~650 MB) bundled in `app/src/main/assets/audio/<id>.mp3`; **no `INTERNET` permission**; the streaming-recovery layer disappears.
3. Background playback fixed with Media3 ExoPlayer + `MediaSessionService` (foreground service, `mediaPlayback`, wake lock, `MediaStyle` notification with play/pause/±15 s/next + artwork).
4. Seek fixed with drag-preview + commit-on-release.
5. Package `com.julicuentos.app` — the RN build is uninstalled once (different signing key).
6. "Play now" no longer clears the queue.
7. End of queue: playback **stops**; manual `⏭` still **wraps** the catalog.
8. Timer 15/30/45 + end-of-story + off; ~10 s fade to pause; `elapsedRealtime`; queue preserved; `end_of_story` suppresses auto-advance.
9. "Noche de cuentos" UI: same palette and faces, flat, duration chip, adaptive 5/3/2 grid, dual-orientation split player, Spanish copy.
10. Future slices: kid-lock, repeat, favorites, per-story history, online features, new stories.
11. Success criteria: (a)–(e) below.

## Affected areas

- **This repo** (`julicuentos-native`): created from scratch — Gradle project, manifest, Kotlin sources, resources, `assets/` (20 MP3s already present, ~650 MB; catalog data and images still to be ported).
- **Source RN repo** (`~/julicuentos-rn`): read-only. Not modified, not deleted; it stays available as the reference implementation and as a reinstallable fallback.
- **The device**: one-time uninstall of the RN `com.julicuentos.app` build (signature mismatch makes an in-place upgrade impossible), losing the stored queue/position/1-key preference. From then on, plain `adb install -r` upgrades.
- **Daily usage**: the sleep flow gains a notification/lock-screen control set and a fade-out ending — visible changes for the parent, none for the child's mental model (same screens, same words).

## Alternatives considered

| Option | Why it was rejected |
|---|---|
| **Keep RN/Expo and patch it** — add a real background-audio module (config plugin + `android/` prebuild), rewrite `SeekBar` with local drag state, bundle audio via `expo-asset`. | The honest version of this ends up writing Kotlin anyway, inside a generated `android/` tree, while keeping the codegen hooks, the `expo-av` ceiling and every re-render workaround. The seek fix is a fight with the responder system + a `ScrollView` that can steal the gesture; the recovery layer stays because a bundled-asset player in `expo-av` still has no MediaSession or wake lock. Cost paid per feature is high for a one-device app; the residual failure mode (screen-off death) is still third-party. **Rejected: maximum effort for parity, not improvement.** |
| **Native + Jetpack Compose.** | Less boilerplate, modern, better tooling — but recomposition on a 1.3 GHz Cortex-A53 / API 22 is exactly the cost the RN code spent months avoiding (no fades, no elevation, 500 ms ticks). A progress signal at 2–4 Hz must be scoped tightly or it re-renders a screen. Views + `RecyclerView` + `ConstraintLayout` give predictable, targeted invalidation for the same amount of code here. **Rejected on hardware grounds; reversible later.** |
| **Native, but keep streaming** (small APK, audio on the CDN). | APK drops to ~10 MB, but the app then needs the network to work at bedtime, the recovery layer must be re-implemented in Kotlin, and seeks land in unbuffered territory — i.e. the two reported bugs partly survive. The 650 MB is a one-time cost; the streaming penalty is paid every night. **Rejected.** |
| **`MediaPlayer` + manual notification** instead of Media3. | Smaller dependency graph and no Media3-on-22 unknowns — but re-implementing queue handling, wake mode, offset seeking, `MediaSession`/`MediaButton`, and the `MediaStyle` contract by hand is more code than `androidx.media3:1.x` (minSdk 21) and is where subtle bugs live. **Rejected, with the pre-O notification behavior called out as a risk to verify early.** |

## Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| **~650–700 MB APK on a 16 GB device**: `adb install` stages the file twice (`/data/local/tmp` + install), so a nearly-full tablet can fail the install. | Blocked delivery, late. | Check free space before building the UI; use `adb --no-local install` (streamed) as the documented default fallback; keep one APK, no splits; measure final size in the first slice, not the last. |
| **Large single asset playback on a 2015 kernel** — ~52 min MP3s (up to ~60 MB) from `assets/` can hit `AssetFileDescriptor` length/offset limits on old platforms. | Audio silently unplayable on the target device — the whole premise. | **First implementation task is a device spike**: bundle one long MP3, play it from `assets/` through Media3 on the real device. Fallback pre-agreed in design: copy audio to app-private files on first install (still no network), keeping the same player code. |
| **Media3 pre-O notification differences** — API 22 has no notification channels, and `MediaStyle`/action semantics differ; Fire OS 5.3 adds its own launcher behavior. | Missing or non-interactive notification → background playback "works" but looks broken. | Verify on the device, never only an emulator; minimal action set (play/pause, ±15, next); keep a fallback to `NotificationCompat.MediaStyle` from `androidx.media` if the Media3 session notification misbehaves on 22. |
| **Same package name, different signing key.** | `INSTALL_FAILED_UPDATE_INCOMPATIBLE` on first install; the old queue/position is lost. | Documented as an explicit one-time uninstall step; keep `versionCode` monotonic thereafter; no data migration promised. |
| **Bitmap memory in a 5-column landscape grid** — 1400×1400 covers decode to ~7.8 MB each and would OOM the ART heap. | Crashes on the catalog, the first screen. | Preserve the thumbnail/cover split (512 px thumbnails in the grid), downsample to view size, `RGB_565` for large covers, small `LruCache` (8–16 MB), or bake downscaled variants into the APK. |
| **Fire OS 5.3 kills background processes** even with a correct foreground service. | Bug #1 returns through a different door. | Wake lock + `mediaPlayback` FGS + real verification (**30 min screen-off**, not 3 min); if still killed, design decides whether to request a battery-optimization exemption. |
| **Flat-design constraint misread as "make it prettier with shadows".** | Regression on exactly the hardware quirk that forced the flat design (jagged rasterized rounded elevation, costly alpha). | Treat "no elevation, no blur, no animated vectors, minimal overdraw" as a spec requirement, not a style preference. |
| **Host toolchain drift** (AGP/Gradle/Kotlin/JDK combinations that emit API 22 bytecode and build 34). | Unreproducible builds. | Pin everything in the Gradle wrapper and version catalog; verify `assembleDebug` on the host as part of slice 1. |
| **Asset/catalog integrity**: a missing or truncated MP3, or catalog durations that disagree with the real files. | One story unplayable; wrong duration chips and a seek bar that clamps unexpectedly. | Verify all 20 files exist with sane sizes and that real durations match the catalog data as a pre-condition task; the app must degrade gracefully (error state) for a missing file. |

## Rollback

- The native work is additive: it lives entirely in this new repo. Nothing in the RN project is modified or deleted, so the previous app remains buildable and installable at any point.
- On the device, rollback is **uninstall the native APK → reinstall the RN build** (signature mismatch means no in-place downgrade). Stored state is one small JSON blob with no value worth preserving, so rollback is lossless in practice.
- Within the native project, each slice keeps `assembleDebug` green, so a regression rolls back to the previous commit/APK rather than being partially fixed.

## Success criteria

An implementation is accepted when all five are demonstrated **on the Fire HD 10**, not in an emulator:

- **(a) Background reliability** — playback continues for **≥ 30 minutes with the screen off**, from a real start-to-finish story, without the process being killed.
- **(b) Seek quality** — the thumb tracks the finger with no backward snapping, the target timestamp is visible while dragging, and exactly one seek happens on release, landing where the finger left the bar; scrubbing inside a scrollable/landscape layout is never stolen by scrolling.
- **(c) Cold restore** — force-stop and reopen resumes the last story **paused at the last position** (no autoplay), with the queue intact.
- **(d) Feature parity** — every catalog / queue / timer capability of the RN app exists and behaves as specified (including the deliberate differences: play-now keeps the queue, timer fades).
- **(e) Build and install** — `./gradlew assembleDebug` produces the APK and `adb install -r` succeeds on the device.

Supporting (non-blocking) expectations: ~700 MB APK installs with the documented procedure; the notification shows artwork and responds to play/pause, ±15 s and next; no `INTERNET` permission in the merged manifest; no screen uses the system font; every catalog card shows a correct duration.

## Open questions

**None that block this proposal.** The following are technical unknowns handed to the design phase with a pre-agreed fallback, so no product decision is pending:

1. **Spike:** can Media3 play a ~60 MB MP3 directly from `assets/` on API 22? If not, design chooses the first-run copy-to-private-storage path (no network involved). *(Risk row 2.)*
2. **Notification implementation** on pre-O: Media3 session notification vs `androidx.media` `NotificationCompat.MediaStyle`. *(Risk row 3.)*
3. **Whether Fire OS needs a battery-optimization exemption** despite a correct foreground service — decided from the (a) verification result. *(Risk row 6.)*
4. **Catalog data carrier:** Kotlin constant list vs `stories.json` asset — both satisfy "compiled in"; design picks on editability.
5. **Catalog order** stays alphabetical (RN parity, and the circular `⏭` depends on it). If the owner later wants duration-based or "most listened" ordering, it is a separate change — no re-ordering ships in this one.
