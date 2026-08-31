# Tasks — migrate-to-native-android

> 5 ordered slices, each independently buildable (`./gradlew assembleDebug` green, `versionCode++`, installable on Fire HD 10 API 22). References: `design.md` (D1–D10), `specs/<capability>/spec.md`.

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~3900–4600 total (resources/scripts/generated included; 650 MB MP3s gitignored, out of review surface) |
| 400-line budget risk | High |
| Chained PRs recommended | Yes |
| Suggested split | PR 1 scaffold+spike → PR 2 catalog+playback core → PR 3 catalog UI → PR 4 player UI → PR 5 queue+timer+persistence+polish |
| Delivery strategy | auto-chain |
| Chain strategy | stacked-to-main |

```text
Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High
```

## Slice 1 — Scaffold + device spike

Est: ~900 lines (mostly generated: wrapper, resources, script, stories.json) + binary fonts/mipmaps. Spike verdict gates slice 2.

- [x] S1.1 Gradle wrapper: committed `gradle/wrapper/*` + `gradlew` + `gradlew.bat`; `distributionUrl=file:~/Documents/www/julicuentos-native/tools/gradle-dist/gradle-8.7-bin.zip` (absolute file URL per slice-1 delegation — D9's documented fallback for this single-machine project); `./gradlew --version` verified against the local zip. Note: bootstrap required a `settings.gradle.kts` first (Gradle 8.7 refuses `wrapper` on a dir without one) and the dist zip was renamed `gradle.zip` → `gradle-8.7-bin.zip` to match the URL.
- [x] S1.2 `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties` (useAndroidX); `gradle/libs.versions.toml` pins per D9 table (AGP 8.5.2, Kotlin 1.9.24, media3 1.2.1, appcompat 1.6.1, fragment 1.6.2, recyclerview 1.3.2, constraintlayout 2.1.4, androidx.media 1.7.0, junit 4.13.2; core pinned `1.12.0` per slice-1 delegation — resolved fine, `1.12.1` fallback not needed). Deviation: plugin `alias()` declarations live in the ROOT `build.gradle.kts` plugins block instead of the settings plugins block — Gradle 8.7 cannot resolve toml catalog aliases from settings; pins remain single-sourced in the toml.
- [x] S1.3 `app/build.gradle.kts`: applicationId `com.julicuentos.app`, minSdk 22 / compileSdk 34 / targetSdk 34, `noCompress += "mp3"`, `minifyEnabled false`, versionCode 1, versionName "1.0", debug signing, Kotlin/Java 17, no desugaring.
- [x] S1.4 `AndroidManifest.xml`: FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, WAKE_LOCK, POST_NOTIFICATIONS; INTERNET absent — verified via `aapt dump permissions` (the merged manifest additionally carries the auto-generated `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` that androidx.core 1.12 always adds; app-defined, normal-protection, not a network permission). PlaybackService/MediaButtonReceiver declarations land in slice 2.
- [ ] S1.5 Theme skeleton: `colors.xml` (6 tokens + 5 literals, D6), `themes.xml` `Theme.Julicuentos` (AppCompat.NoActionBar, windowBackground dark splash, statusBarColor #17152E), `styles.xml` `TextAppearance.Jc` ladder 30/24/20/18/16/14/12 (specs/theme-design "Locked palette tokens"). [partial — colors.xml (7 tokens per delegation), themes.xml and Spanish strings.xml base done; `styles.xml` ladder + dimens + dark splash drawable deferred — outside slice-1 delegated scope, lands with UI slices]
- [ ] S1.6 Fonts: copy Fredoka SemiBold + Nunito Sans R/B TTFs from RN `node_modules/@expo-google-fonts/*` → `res/font/` + fontFamily XMLs wired to ladder — verify no screen uses system font (specs/theme-design "Bundled fonts and TextAppearances"). [partial — fredoka_semibold.ttf / nunitosans_regular.ttf / nunitosans_bold.ttf copied; fontFamily XMLs + ladder wiring deferred with S1.5]
- [ ] S1.7 Legacy PNG mipmaps (all densities, no adaptive icons on 22) + dark splash drawable (specs/theme-design "Launch, splash and system chrome"). [partial — ic_launcher.png composited over #17152E at mdpi 48 / hdpi 72 / xhdpi 96 / xxhdpi 144; dark splash drawable deferred with S1.5]
- [x] S1.8 `tools/port-catalog.py` (stdlib only): `content/cuentos.tsv` + `content/<id>/cover.jpg|thumbnail.jpg` → `app/src/main/assets/stories.json` (20 entries, alphabetical, validator-checked) + `assets/covers/<id>/cover.jpg` + `assets/covers/<id>/thumbnail.jpg` (paths per slice-1 delegation; design §4's separate `thumbs/` dir replaced by JSON-carried `cover`/`thumbnail` fields); inline fixes applied: bambi synopsis prefix "ambi"→"Bambi", "Los Increibles"→"Los Increíbles", whitespace stripped; validated 20/20 images exist. Duration probe ±2 s NOT run — the audio dir was declared off-limits for this slice (do-not-touch/do-not-list); catalog durations remain placeholders (real metadata wins post-prepare per specs/playback).
- [x] S1.9 `.gitignore`: `app/src/main/assets/audio/`, `audio-download.log|.pid` already present; 20 MP3s confirmed on disk, none staged (git status clean apart from expected scaffold files; `git check-ignore` covers audio only).
- [ ] S1.10 DEVICE SPIKE build: temp one-activity play button, only `rompe-ralph.mp3` (~49 MB) bundled, Media3 `AssetDataSource` via `asset:///audio/rompe-ralph.mp3`; `./gradlew assembleDebug` green; `apkanalyzer` verify mp3 entry STORED; record APK size + first-build time. [deferred-device — superseded in part: the full-catalog build (all 20 MP3s already bundled) is green and all 20 entries verified STORED + APK size recorded; the temporary single-asset spike build and on-device play/seek/screen-off checks move to the next device session]
- [ ] S1.11 Spike verdict on Fire HD 10: start <2 s; `seekTo(46:00)` then `seekTo(51:00)` audio continues; natural end fires ENDED; screen-off 10 min → position ≈ +600 s; logcat clean of `Source error`. Record PASS/FAIL in this file. FAIL → wire `AudioSourceResolver` copy-to-filesDir gate (design D1 fallback) before slice 2 (specs/delivery "Bundled assets and long-file spike"). [deferred-device — needs the Fire HD 10 session]
- [ ] S1.12 Gate: `assembleDebug` green + `adb install -r` OK + spike verdict recorded; versionCode stays 1. [partial — assembleDebug green, versionCode 1; `adb install -r` + spike verdict deferred-device]

## Slice 2 — Catalog data + playback core

Est: ~900 lines (pure logic + tests, service, notification provider). Depends on S1 spike verdict.

- [x] S2.1 `catalog/Story.kt` + `CatalogParser.kt` (org.json, validator-first: drop bad/missing id `^[a-z0-9-]+$` or titulo, coerce `duracionSegundos` ≥0 else 0) + `Catalog.kt` (parse `assets/stories.json` on worker thread before any hydration; sort by id; `byId` map); JUnit RED→GREEN (specs/catalog "Compiled-in catalog data").
- [ ] S2.2 `playback/QueueLogic.kt` pure: enqueue append + de-dup, moveUp/moveDown, remove, clear; next-resolution: manual ⏭ wraps catalog circularly, auto-advance stops when queue empty (queue preserved); JUnit (specs/queue "Enqueue is append + de-duplicated", "Reorder and remove", "Next resolution and end-of-queue"). (deferred-device; full queue logic is slice 5)
- [ ] S2.3 `playback/SleepTimer.kt` + `Fade.kt` pure: `endsAt = elapsedRealtime() + minutes*60_000`; parse window: ≤0 → off, > minutes+60 s (reboot) → off, else restore countdown; 10 × 1 s fade schedule 1.0→0.0; `end_of_story` mode; JUnit (specs/sleep-timer "Minutes countdown (screen-off-proof)", "Expiry = ~10 s fade…"). (deferred-device; timer lands with slice 5)
- [x] S2.4 `playback/AudioSourceResolver.kt`: default `asset:///audio/<id>.mp3`; if spike FAILED → first-run copy assets→`filesDir/audio/` with per-file `.done` marker, URIs `file://` (design D1).
- [x] S2.5 `playback/PlaybackService.kt`: MediaSessionService + the ONLY ExoPlayer; `setAudioAttributes(USAGE_MEDIA, CONTENT_TYPE_MUSIC, handleAudioFocus=true)`, `setHandleAudioBecomingNoisy(true)`, `setWakeMode(WAKE_MODE_LOCAL)`; custom SessionCommands SKIP_BACK_15/SKIP_FWD_15; manifest: service exported + foregroundServiceType mediaPlayback + MediaButtonReceiver (specs/playback "Single ExoPlayer source of truth", "Audio focus"; specs/delivery "Manifest and permissions").
- [x] S2.6 Load contract: `playNow(D)` → state `Loading(D, pos 0)` before player touch → `setMediaItem(item(D), 0)` + prepare + playWhenReady; monotonic `loadGen` stale-event guard; autoplay rules; end-of-story w/ empty queue → stop, playing=false, queue preserved (specs/playback "Load-story contract", "Autoplay rules", "End of story vs manual next (queue asymmetry)").
- [x] S2.7 `playback/PlaybackRepository.kt`: MediaController connect (async, retried), translation to 7-state machine (userPaused latch: cold restore lands Ready not Paused), `StateListener`/`ProgressListener` registries (CopyOnWriteArrayList), 500 ms main-Handler progress ticks; `playStoryNow/next/togglePlayPause/seekTo/±15` API.
- [x] S2.8 `notify/MediaNotificationProvider.kt`: custom provider, `NotificationCompat.MediaStyle`; channel "playback" only if SDK≥26; actions play/pause, −15, +15, next (compact 0–2); artwork `covers/<id>.jpg` decode ≤256 px RGB_565 on transition; post on Playing/Ready-with-content, keep on pause, cancel on Idle/Ended/taskRemoved-no-playback; stopSelf when idle (specs/playback "Media notification and media buttons").
- [ ] S2.9 Gate: `./gradlew test` green; install; play from assets; notification interactive (play/pause/±15/next) on device; screen-off 5 min sanity; versionCode++. (deferred-device: host assembleDebug green, versionCode bumped to 2; device install/run pending) (deferred-device: host ``assembleDebug`` green, versionCode bumped to 2; device install/run pending)

## Slice 3 — Catalog UI

Est: ~700 lines (layouts, adapter, bitmap pipeline).

- [ ] S3.1 `MainActivity` (Theme.Julicuentos, single activity) + fragment nav via `supportFragmentManager` back stack — show(BACK_STACK_ENTRY); no nav library, no nav args (design D5) (specs/theme-design "Launch, splash and system chrome").
- [ ] S3.2 `layout/fragment_catalog.xml` + `item_story_card.xml`; grid columns via integer resources: `values`=2 / `values-w768dp`=3 / `values-w1024dp`=5 → `GridLayoutManager(span)` (specs/catalog "Adaptive grid columns").
- [ ] S3.3 `ui/catalog/StoryAdapter.kt`: bind-once cells; cover + title + 2-line synopsis + duration chip + "Sonando" pill moved via `notifyItemChanged(payload)` on current-story change only; grid cells never subscribe to progress (specs/catalog "Story card composition"; specs/playback "Progress updates are targeted, not global").
- [ ] S3.4 `media/Bitmaps.kt` + `ThumbCache.kt`: decode `thumbs/<id>.jpg` with inSampleSize ≤~512 px, RGB_565, LruCache min(12 MB, heap/8), single-thread executor + main-post; covers ≤640 px 2-entry cache for player/mini (specs/catalog "Grid thumbnail budget (no OOM)").
- [ ] S3.5 `StoryActionSheet` DialogFragment (⋮): Reproducir ahora / Añadir a la cola / Cancelar — sheet actions route to repository (specs/catalog "Story action sheet (⋮)").
- [ ] S3.6 Tap semantics `resolveOpenStoryAction(D)`: current+Playing → open player; current+Paused → togglePlayPause + open; else `playNow(D)` → nav Player immediately (no await of prepare). **BEHAVIOR CHANGE: play-now preserves the queue** (specs/catalog "Row tap semantics (resolveOpenStoryAction)"; specs/queue "Play-now preserves the queue").
- [ ] S3.7 `MiniPlayerView` in-flow footer (never overlay): cover, title, play/pause, non-interactive `MiniProgressStrip` (#3D3860 track / #72E0B8 fill, `isEnabled=false`); hidden when Idle; tap → PlayerFragment (specs/catalog "Miniplayer").
- [ ] S3.8 Gate on device: column counts at each width bucket; 5-col landscape fling without OOM; pill follows current story; miniplayer strip animates; versionCode++.

## Slice 4 — Player UI

Est: ~600 lines (layouts, custom views, seek controller).

- [ ] S4.1 `layout/fragment_player.xml` portrait: centered non-scrolling ConstraintLayout, content fits 800×752 dp, description elides to 2 lines — seek bar structurally outside any scroll container (specs/theme-design "Dual-orientation player layouts"; specs/playback "Seek…").
- [ ] S4.2 `layout-w600dp-land/fragment_player.xml` split: cover left / ≤520 dp controls column right, gap 28, content ≤1100 dp, no scroll; `values-land/` compact dimens + `values-land-h720dp/` roomy restore.
- [ ] S4.3 `ui/player/CoverHaloView.kt`: FrameLayout ImageView + one onDraw pass — base mint ring rgba(114,224,184,0.28), 4 square-ring segments (corner ratio 0.12) lighting at >2 %/>28 %/>55 %/>80 %, 3 dp peach outer ring when timer active; redraws at 500 ms without touching bitmap (specs/theme-design "Cover art square-ring progress").
- [ ] S4.4 `ui/player/SeekBarController.kt` + seek bar visuals: onStartTracking → dragging=true, suppress progress writes; onProgressChanged → thumb=finger + preview label `formatTime(ratio*duration)`, ZERO player calls; onStopTracking → exactly one `seekTo(target)`; thumb stays at release point, no snap-back; layer-list track rgba(170,163,206,0.4)/#72E0B8 + 16 dp flat thumb (specs/playback "Seek = drag-preview + commit-on-release").
- [ ] S4.5 `ui/player/PlayerFragment.kt`: bind repository snapshot; transport row play/pause, −15 s, +15 s, next (wraps circularly); "Ver cola" + timer buttons → queue/timer fragments; empty/Idle state (specs/playback "Play/pause and transport", "End of story vs manual next (queue asymmetry)").
- [ ] S4.6 `common/TimeFormat.kt` pure `formatTime` (m:ss / h:mm:ss) + `formatRemaining` + JUnit boundary table (specs/theme-design "Time formatting").
- [ ] S4.7 Duration from real metadata: ExoPlayer `duration` post-prepare overrides catalog placeholder in title/timer line + catalog chip (specs/playback "Duration from real metadata").
- [ ] S4.8 Gate on device (success criterion b): thumb tracks finger, no backward snap, target time visible while dragging, one seek on release, no gesture theft in landscape split layout; versionCode++.

## Slice 5 — Queue + Timer + Persistence + polish

Est: ~800 lines (queue/timer UI, persistence, restore, docs, acceptance pass).

- [ ] S5.1 `ui/queue/QueueFragment.kt` + `QueueAdapter.kt`: ordered list (cover, title, position), move up/down, remove, "Vaciar", corrected empty-state copy; RecyclerView never re-binds on progress (specs/queue "Queue screen").
- [ ] S5.2 `ui/timer/TimerFragment.kt`: 5 static option rows — 15 / 30 / 45 min / "Al terminar este cuento" / "Desactivar"; selection routes to service (specs/sleep-timer "Timer selection UI", "Timer modes").
- [ ] S5.3 Service timer wiring: 1 Hz Handler on elapsedRealtime publishes remaining; expiry → 10 × 1 s volume fade → pause() + volume 1.0 + timer=off + immediate store.flush, queue untouched; `end_of_story` expiry → pause, timer off, NO auto-advance; cancel rules: user pause/play/timer change mid-fade → cancel fade, volume 1.0, timer off (specs/sleep-timer "Expiry = ~10 s fade…", "End-of-story timer suppresses auto-advance").
- [ ] S5.4 Timer visibility: 1 Hz countdown line on player + peach ring segment on CoverHaloView; both cleared on expiry/off (specs/sleep-timer "Timer visibility on the player").
- [ ] S5.5 `persist/PersistedState.kt` + `PlayerStore.kt`: one SharedPreferences key; schema `currentStoryId/positionMs/queueIds/timer/updatedAt` (timer.endsAt = elapsedRealtime anchor, D3); tolerant parser + safe defaults; JUnit (specs/persistence "Single storage key, fixed JSON schema", "Tolerant parser with safe defaults").
- [ ] S5.6 Flush cadence: 5 s Handler + immediate flush on service onStop/onDestroy; writes impossible before `hydrationComplete` (early-return) (specs/persistence "Flush cadence (5 s + onStop)", "Never write before hydration").
- [ ] S5.7 `persist/RestoreCoordinator.kt`: connect → parse → if userMutated discard snapshot (user wins); unknown currentStoryId → Idle; unknown queue ids filtered; expired/invalid timer → off (no pause, story/queue restore normally); else prepare(current, positionMs, autoplay=FALSE) → Ready; JUnit (specs/persistence "Never overwrite user actions taken during restore", "Cold restore is paused at the last position", "Restore drops unknown ids").
- [ ] S5.8 `strings.xml` full Spanish registry pass (verbatim theme-spec list + corrected queue empty copy); audit: 52 dp min touch targets everywhere; flat audit — no elevation/shadow/blur/AVD anywhere (specs/theme-design "Spanish strings registry", "Minimum touch targets 52 dp", "Flat by hardware constraint").
- [ ] S5.9 `docs/delivery.md`: build + `adb install -r` flow with `--no-local` streamed fallback; ONE-TIME uninstall of RN `com.julicuentos.app` (signature mismatch) before first install; versionCode monotonic-bump policy per installed slice; free-space check (specs/delivery "Install procedure and versioning").
- [ ] S5.10 DEVICE ACCEPTANCE RUN vs checklist (specs/delivery "Device acceptance checklist"): (a) screen-off ≥30 min full story; (b) seek contract; (c) force-stop → reopen paused at position, queue intact; (d) full parity incl. play-now-keeps-queue + timer fade; (e) assembleDebug + adb install; plus `aapt dump permissions` shows no INTERNET; notification artwork + 4 actions; duration chip correct on all 20 cards; versionCode final bump.

## Per-slice forecast (review workload)

| Slice | Est. changed lines | >400 budget | Recommendation |
|-------|--------------------|-------------|----------------|
| 1 Scaffold + spike | ~900 (wrapper/gradle/resources/script/stories.json generated; fonts+mipmaps binary) | Yes | Own PR (PR 1) — low review density, mostly pinned config |
| 2 Catalog + playback core | ~900 (Kotlin logic + tests + service + provider) | Yes | Own PR (PR 2) — highest-density review slice |
| 3 Catalog UI | ~700 | Yes | Own PR (PR 3) |
| 4 Player UI | ~600 | Yes | Own PR (PR 4) |
| 5 Queue + Timer + Persistence + polish | ~800 | Yes | Own PR (PR 5) — split 5a/5b if review overruns (design D10) |

All slices exceed 400 lines → chained PRs confirmed (stacked-to-main, one PR per slice, each leaves `assembleDebug` green + `versionCode++`). MP3 binaries are gitignored and never enter a review diff (design §7 risk).

## Device-verification task list (Fire HD 10 2015, API 22 — never emulator-only)

- [ ] DV1 Spike: longest asset plays/seeks/ends from `assets/`, screen-off 10 min (S1.11; specs/delivery "Bundled assets and long-file spike")
- [x] DV2 STORED mp3 entries + no INTERNET permission (`apkanalyzer` / `aapt dump permissions`) (S1.10, S1.4) — verified on host: `aapt dump permissions` shows exactly the 4 declared permissions (+ androidx.core's auto-generated DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION), no INTERNET; `unzip -v` shows all 20 `assets/audio/*.mp3` STORED.
- [ ] DV3 Notification interactive: play/pause, −15, +15, next, artwork; survives pause at shade (S2.9; specs/playback "Media notification and media buttons")
- [ ] DV4 Screen-off playback ≥30 min, process alive (S5.10; specs/playback "Background playback (success criterion a)")
- [ ] DV5 Seek: no snap-back, preview visible, one seekTo on release, portrait + landscape (S4.8; specs/playback "Seek = drag-preview + commit-on-release")
- [ ] DV6 Cold restore: force-stop → reopen paused at position, queue intact, unknown ids dropped (S5.10; specs/persistence "Cold restore…")
- [ ] DV7 Timer: 15/30/45 countdown survives screen-off; fade ≈10 s to pause; queue preserved; end-of-story suppresses advance (S5.10; specs/sleep-timer)
- [ ] DV8 Queue parity: append+dedup, reorder, remove, clear, manual next wraps, auto-advance stops at empty (S5.10; specs/queue)
- [ ] DV9 Grid 5/3/2 columns + no OOM fling + "Sonando" pill tracking (S3.8; specs/catalog)
- [ ] DV10 Flat/fonts/touch audit: no elevation/blur, no system font, 52 dp targets, duration chips correct (S5.8; specs/theme-design)
- [ ] DV11 Install procedure incl. one-time RN uninstall + `--no-local` fallback documented and exercised (S5.9; specs/delivery "Install procedure and versioning")
