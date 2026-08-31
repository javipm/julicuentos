# Design — migrate-to-native-android

**Phase:** design · **Date:** 2026-08-30 · **Inputs:** proposal.md, explore.md, 7 capability specs (catalog, playback, queue, sleep-timer, persistence, theme-design, delivery).
**Locked by proposal (not re-decided here):** Kotlin + XML views (no Compose), `minSdk 22` / `compileSdk 34`, package `com.julicuentos.app`, Media3 ExoPlayer + MediaSessionService, all 20 MP3s bundled (`assets/audio/<id>.mp3`, no `INTERNET`), Gradle 8.7 + AGP 8.x + JDK 17, flat design (no elevation/shadows/blur), target Fire HD 10 2015 (API 22, Fire OS 5.3). Every decision below states **chosen / tradeoffs / rejected**.

---

## 1. Architecture overview

Single Gradle module `:app`, single Activity, four screens as Fragments, one playback service owning the only audio stream. No DI, no coroutines, no ViewModel — a process-scoped repository bridges UI ↔ service via Media3 `MediaController`.

```
                     ┌──────────────────────────────────────────────────────┐
                     │ Process com.julicuentos.app                          │
 ┌──────────────┐    │  ┌──────────────── UI (main thread) ───────────────┐ │
 │ Fire HD 10   │    │  │ MainActivity (Theme.Julicuentos, #17152E)       │ │
 │ Fire OS 5.3  │    │  │  ├─ CatalogFragment  (grid + card + ⋮ sheet +   │ │
 │ API 22       │◄─adb│  │  │                       in-flow MiniPlayer)    │ │
 └──────────────┘    │  │  ├─ PlayerFragment   (stack / split layouts)    │ │
                     │  │  ├─ QueueFragment    (RecyclerView)             │ │
                     │  │  └─ TimerFragment    (5 static option rows)     │ │
                     │  │        ▲ observe state + 500 ms progress ticks  │ │
                     │  └────────┼────────────────────────────────────────┘ │
                     │           │ listeners / commands (same process)      │
                     │  ┌────────▼───────────────────────────────────────┐ │
                     │  │ PlaybackRepository (singleton, process-scope)  │ │
                     │  │  - MediaController (client of the session)     │ │
                     │  │  - PlayerState sealed machine (Idle…Error)     │ │
                     │  │  - progress snapshot + Handler cadence (500ms) │ │
                     │  │  - RestoreCoordinator (hydration + user-wins)  │ │
                     │  └────────┬───────────────────────────────────────┘ │
                     │           │ MediaController/Session IPC             │
                     │  ┌────────▼───────────────────────────────────────┐ │
                     │  │ PlaybackService : MediaSessionService (FGS     │ │
                     │  │  mediaPlayback, WAKE_LOCK, notification)       │ │
                     │  │  ├─ ExoPlayer (the ONLY player instance)       │ │
                     │  │  │   setWakeMode(LOCAL), AudioAttributes,      │ │
                     │  │  │   handleAudioFocus=true, becomingNoisy=true │ │
                     │  │  ├─ QueueLogic (pure) + next-resolution        │ │
                     │  │  ├─ SleepTimer (Handler on elapsedRealtime)    │ │
                     │  │  │   + 10-step fade → pause                    │ │
                     │  │  ├─ NotificationProvider (MediaStyle)          │ │
                     │  │  └─ PlayerStore (5 s flush + onStop/onDestroy) │ │
                     │  └────────┬───────────────────────────────────────┘ │
                     │           │ DataSource                              │
                     │  ┌────────▼────────────┐  ┌───────────────────────┐ │
                     │  │ AssetDataSource     │  │ (fallback) file://    │ │
                     │  │ asset:///audio/*.mp3│  │ filesDir/audio/*.mp3  │ │
                     │  └─────────────────────┘  └───────────────────────┘ │
                     │  catalog: Catalog (stories.json → Story[20],        │
                     │  alphabetical, byId map) · Bitmaps (LruCache)       │
                     └──────────────────────────────────────────────────────┘
```

**Layering rule:** `ui/*` may call `PlaybackRepository` and `Catalog`; `playback/*` may call `persist/*` and `catalog`; nothing in `ui` talks to ExoPlayer directly; `persist/*` and `QueueLogic`/`TimerLogic` are pure Kotlin (unit-testable, no Android imports where possible).

### Component responsibilities

| Component | Owns |
|---|---|
| `PlaybackService` | ExoPlayer instance, MediaSession, FGS lifecycle + notification, audio attributes/focus config, wake mode, timer + fade, persistence flush timer |
| `PlaybackRepository` | MediaController connection (async, retried), state machine translation (ExoPlayer events + load-generation guard → `Idle/Loading/Ready/Playing/Paused/Ended/Error`), listener registry, 500 ms progress ticks, load-story contract (select state → replace media item → prepare), autoplay rules |
| `QueueLogic` / `TimerLogic` / `PersistedState` / `TimeFormat` / `RestoreCoordinator` | Pure logic, JUnit-tested: enqueue/dedup/move/next-resolution, timer math + fade schedule + validity window, tolerant JSON parse, `formatTime`/`formatRemaining`, hydration guards |
| `Catalog` | Parse `assets/stories.json` validator-first (drop unknown/bad entries), sort by id, `byId` map |
| `Bitmaps` / `ThumbCache` | Asset decode with `inSampleSize`, `RGB_565`, 12 MB LruCache (thumbnails only) |
| Screens | Pure view work: bind state snapshots, forward intents to repository. No business logic in Fragments |

### State machine (spec: Idle/Loading/Ready/Playing/Paused/Ended/Error)

Derived in `PlaybackRepository` from ExoPlayer (`STATE_IDLE/BUFFERING/READY/ENDED`, `playWhenReady`, errors) + app intent flags:

- `Idle` — nothing current (fresh install / unknown-id restore).
- `Loading(story, autoplay)` — `playStoryNow/next/advance` set this **before** touching the player (load contract step 1).
- `Ready` — prepared, paused, not yet started (cold restore lands here).
- `Playing` / `Paused` — derived from `playWhenReady && state==READY` plus a `userPaused` latch so restore (`playWhenReady=false`) is `Ready`, not `Paused`.
- `Ended` — `onMediaItemTransition(reason=AUTO)` suppressed by empty queue → stop, playing=false, queue preserved.
- `Error(msg)` — `onPlayerError`; nothing auto-recovers (no recovery layer by spec).

Stale-event guard: a monotonically increasing `loadGen` accompanies every load; repository ignores player events whose `mediaItem`/generation is superseded (port of RN's `loadGenRef`, needed for "rapid switching stays sane").

---

## 2. Decisions

### D1 — Large-asset playback on API 22 (spike + fallback)

**Chosen (primary):** Media3 `AssetDataSource` via `MediaItem.fromUri("asset:///audio/<id>.mp3")` resolved by `DefaultDataSource` (never `res/raw` — dashes in ids). Enabled by `androidResources { noCompress += "mp3" }`, which keeps MP3 entries STORED; stored assets are mmap-backed, so `AssetInputStream.skip()` (used by `AssetDataSource.seekTo`) is an O(1) position move, not a read-through. The `AssetFileDescriptor` length/offset landmines documented on old platforms mostly affect `openFd()`/`MediaMetadataRetriever` consumers and *compressed* entries; the AssetDataSource `InputStream` route avoids `openFd()` entirely. Fallbacks only if the spike proves otherwise.

**Spike task (must be task #1 in tasks.md, before any UI):**
1. Slice-1 scaffold + `noCompress "mp3"` + one activity with a "play" button; bundle **only** `rompe-ralph.mp3` (~49 MB real, longest) as `assets/audio/rompe-ralph.mp3`; temporary `INTERNET`-free minimal manifest.
2. `./gradlew assembleDebug`; `adb install -r app-debug.apk` on Fire HD 10 (serial in explore.md).
3. Play from `assets` on device: (i) starts within ~2 s; (ii) `seekTo(~46:00)` then `seekTo(~51:00)` — audio continues, no error, position advance observed via `Log.d` of `player.currentPosition` every 500 ms; (iii) let it reach natural end — `onPlaybackStateChanged(ENDED)` fires; (iv) screen off 10 min mid-play → position advanced ≈ 600 s on return.
4. Inspect `adb logcat` for `Source error` / `AssetDataSource` exceptions; verify with `apkanalyzer` that `assets/audio/rompe-ralph.mp3` is STORED.
5. Record result (play OK / seek OK / end OK / screen-off OK) in the task; decision recorded before slice 2.

**Fallback ladder (pre-agreed in proposal, wired here):** the repository resolves audio URIs through one seam `AudioSourceResolver`:
1. `asset:///audio/<id>.mp3` (default).
2. If the device run proves asset-direct fails on this kernel → **first-run copy** `assets/audio/*` → `context.filesDir/audio/` (background thread, `.done` marker per file, ~650 MB one-time copy, still zero network) and URIs become `file://`. Same player code, same state machine; catalog unchanged.
3. Last resort (only if *both* above fail — not expected): custom `FileDescriptorDataSource` built on `openFd()` + `DataSpec(fd, offset, length)`.
No zip-aligned AAB / expansion files — delivery is a single sideloaded APK by locked decision.

**Verification on device:** the spike doubles as delivery spec's "longest asset plays from assets/" scenario; §(a) 30-min screen-off run happens on the same build.

**Tradeoffs:** direct assets keep zero-copy simplicity and no first-launch delay; risk is exactly the old-kernel unknown, bounded by the spike being task #1. Copy-to-filesDir trades ~650 MB duplicate disk on a 16 GB device and ~2–5 min first launch for kernel-proof simplicity. DataSpec-on-file-paths was rejected as primary because it forfeits asset-pack simplicity for no gain when the InputStream route already handles STORED entries.

**Rejected:** `res/raw` (name restrictions); shipping MP3s outside the APK (obb — proposal forbids; add complexity with no benefit for sideload); runtime zip extraction (copy fallback already covers the same failure mode more simply).

### D2 — Media notification on API 22

**Chosen:** Media3 `MediaSessionService` **with a custom `MediaNotificationProvider` that builds a `NotificationCompat.MediaStyle` notification** (androidx.media), posted through media3's notification manager contract. This is the "pick one" resolution of Media3-default vs manual MediaStyle: we keep Media3 session/commands/media-button plumbing (which the playback spec needs: play/pause/±15/next + state sync) but own the notification builder, which is the piece with pre-O quirks.

- **Channel guard:** create `NotificationChannel("playback", …)` only when `SDK_INT >= 26`; on 22 `NotificationCompat.Builder(context)` without channel — both paths in one function, branch on `Build.VERSION.SDK_INT`.
- **Actions (exactly the spec set):** play/pause toggle, −15 s, +15 s, next. Compact view = first three (`setShowActionsInCompactView(0,1,2)`), next visible expanded. Each action = `NotificationCompat.Action` with PendingIntent → `MediaButtonReceiver`/session media-button intents; ±15 via custom session commands (`SKIP_BACK_15`/`SKIP_FWD_15` custom `SessionCommand`s so they exist on API 22 where framework skip presets don't).
- **Artwork:** service decodes `assets/covers/<id>.jpg` downsampled to 256 px (inSampleSize + RGB_565) → `setLargeIcon`. One bitmap, cached for the current story only; refreshed on `onMediaItemTransition`.
- **Visibility rules:** post on transition to Playing/Ready-with-content; keep on pause (parent expects controls at the shade); cancel when state → Idle/Ended or `onTaskRemoved` with no playback. `stopSelf` when idle.
- **Fallback clause honored:** if MediaStyle itself misbehaves on Fire OS 5.3 (spike-adjacent check in the playback slice), the same provider switches to a plain `NotificationCompat` with buttons — the provider seam makes this a one-file change.

**Tradeoffs:** custom provider = ~80 lines we maintain; but it is identical on API 22 and 34, avoids debugging media3's default-notification heuristics on Fire OS, and is exactly the fallback the playback spec pre-authorized. Rejected: relying on `DefaultMediaNotificationProvider` (default action layout, custom-command buttons and channel semantics are the least-tested paths on API 22); androidx.media `MediaButtonReceiver`-only service (would mean hand-rolling the whole session layer).

### D3 — Timer storage reconciliation (elapsedRealtime vs persisted `endsAt`)

**Chosen:** the runtime timer is anchored to `SystemClock.elapsedRealtime()` (spec mandate). The persisted field **keeps the schema name `endsAt` but stores the elapsedRealtime anchor**, i.e. at set-time `endsAt = SystemClock.elapsedRealtime() + minutes*60_000`. Restore validity window at parse time:

- `remaining = endsAt - SystemClock.elapsedRealtime()`
- `remaining ≤ 0` → expired → normalize to `off` (this IS the "expired timer does not self-pause" scenario: discarded at parse, no fade scheduled, no pause; story/queue restore normally).
- `remaining > minutes*60_000 + 60_000` (survived a reboot: uptime clock reset, anchor looks far-future) → invalid → `off`.
- else valid → restore countdown at `remaining`; the fade arms only if playback is actually playing when the deadline hits.

Restore is paused-by-definition, so a restored (valid) timer just shows the countdown; expiry of a timer while paused → `off` + no fade (fade only matters to audible audio).

**Edge rules (documented behavior):** manual pause mid-fade cancels the fade and resets volume to 1.0, timer → `off` (user action wins); new timer selection mid-fade cancels the fade; `end_of_story` restore is accepted as-is. `handleAudioFocus`/pause do not touch the timer except expiry/selection paths.

**Tradeoffs:** elapsedRealtime anchor ignores wall-clock jumps (the actual hazard for a bedside app) but dies across reboots — acceptable because the product meaning of the timer ("cut off the night") degrades safely to `off`, which is also the spec's expired-timer behavior. Wall-clock persistence (RN parity) was rejected: the sleep-timer spec explicitly bans wall-clock anchoring for the countdown, and a wall-clock `endsAt` would still need the elapsedRealtime path for the running countdown — two sources of truth.

### D4 — Catalog data carrier

**Chosen:** `app/src/main/assets/stories.json` + covers/thumbnails generated by a small Kotlin-independent Python script (`tools/port-catalog.py`, stdlib only) reading the RN repo's `content/cuentos.tsv` + `content/<id>/cover.jpg|thumbnail.jpg`. Hand-edited later by editing the JSON (script is re-runnable, not required at app build time).

**JSON schema** (array of exactly 20 objects, alphabetical by `id`; app re-sorts defensively and indexes by id):

```json
[
  { "id": "101-dalmatas",           // required, ^[a-z0-9-]+$ — stable slug == assets/audio/<id>.mp3 name
    "titulo": "101 Dálmatas",        // required, non-empty
    "descripcion": "…",              // may be ""
    "duracionSegundos": 1472 }       // int ≥ 0 (placeholder duration; real duration wins after load)
]
```

No `fechaPublicacion`, no audio URLs, no extra fields (catalog spec). Validator-first parse in-app (`CatalogParser` on `org.json`): drop entries with bad/missing id or titulo, coerce `duracionSegundos` to int ≥ 0 else 0, tolerate empty `descripcion`; the compiled catalog MUST end at 20 entries — a build-time script check, plus the app is indifferent to count (self-heals).

**Content fixes live in the script:** `bambi` description prefix fixed, `los-increibles` → title "Los Increíbles" (id stays `los-increibles` — ids are never renamed, they are load-bearing: `assets/audio/<id>.mp3`, restore, circular next).

**Tradeoffs:** JSON asset = one parse (~20 objects, negligible) + one generated file to keep in sync; buys data/code separation and lets the port script carry the content fixes without hand-editing Kotlin. Rejected: hand-written Kotlin list (20 × 300-char Spanish strings in source control of code, merge-hostile, invites drift with covers/audio ids); `res/raw` JSON (dashes fine in assets, keep one assets dir).

### D5 — Module layout, state holder, UI observation, navigation

**Chosen:**

- **Single `:app` module**, packages: `catalog`, `playback`, `persist`, `media` (bitmaps), `ui.*`, `notify`. No `:core`/`:feature` splits — one APK, one team of one, zero build-time payoff for modules.
- **State holder = service + repository, no ViewModel.** `PlaybackService` owns ExoPlayer + MediaSession; `PlaybackRepository` (process singleton) owns the `MediaController`, the state machine snapshot, and listener registries. No lifecycle-viewmodel dependency: the only process-lifetime state lives in the repository and the service; fragments re-subscribe in `onStart`/`onStop`. (No coroutines/StateFlow: plain `CopyOnWriteArrayList<Listener>` + main-thread Handler.)
- **UI observes via `MediaController`, never direct player binding.** Repository translates controller callbacks → `PlayerState` + progress snapshot. Two listener kinds: `StateListener` (coarse) and `ProgressListener` (500 ms Handler cadence = spec cadence ~2 Hz; the seek bar's thumb/time labels, ring, miniplayer strip subscribe here; grid cells never do).
- **UI = 1 Activity + 4 Fragments** (`Catalog` home, `Player`, `Queue`, `Timer`) + a plain `DialogFragment` sheet for ⋮. Fragment transactions on `supportFragmentManager` with back stack (player → queue → timer back-chains naturally). **No navigation library, no nav args** — screens read the shared repository (port of the RN no-params rule).
- **Progress targeting:** only widgets subscribe to progress; a 500 ms `Handler` on the main looper polls `controller.currentPosition/duration` and pushes to current subscribers. Grid never re-binds on progress: `StoryAdapter` binds once, and the "Sonando" pill moves via `notifyItemChanged(payload)` on current-story changes only.
- **Seek bar never inside a scrolling container** (spec/hardware rule): portrait player layout is a centered non-scrolling ConstraintLayout (content fits 800×752 dp; description elides to 2 lines), so RN defect §6.3 is structurally gone.
- **Navigation:** `show(BackStack)` per screen; miniplayer → player; player "Ver cola" → queue; timer opens from player. Rotation recreates the Activity; all state survives in repository/service (chosen over `android:configChanges` surgery).

**Rejected:** Jetpack ViewModel + StateFlow (extra deps + coroutine machinery for zero benefit in a 4-screen app); single-Activity custom view switching (hand-rolled back stack = bugs); multiple Activities (state sharing becomes IPC-by-intent, miniplayer duplication); binding the service and touching ExoPlayer directly from UI (breaks the one-source-of-truth boundary and makes the notification state split-brained).

### D6 — Resources & screens

- **Qualifiers:** `layout/fragment_player.xml` (portrait stack) + `layout-w600dp-land/fragment_player.xml` (split: cover left, ≤ 520 dp controls column right, gap 28, content ≤ 1100 dp, no scroll). Compact spacing via `values-land/dimens.xml` (short-landscape overrides; the Fire's 752 dp height takes the roomy defaults restored in `values-land-h720dp/`). Grid columns via `values/`=`2`, `values-w768dp/`=3, `values-w1024dp/`=5 integers → `GridLayoutManager(span = integer)` (no runtime width math; spec thresholds 1024/768 map 1:1).
- **Custom views (the only ones worth writing):** `CoverHaloView` (FrameLayout: ImageView + one `onDraw` pass drawing base mint ring `rgba(114,224,184,0.28)`, four square-ring segments with corner ratio 0.12 lighting at >2 %/>28 %/>55 %/>80 %, 3 dp peach outer ring when timer active — ring redrawn at 500 ms cadence without touching the bitmap) and `MiniProgressStrip` (track `#3D3860`/fill `#72E0B8`, non-interactive, `view.isEnabled=false` + no click listeners). Seek bar = framework `SeekBar` with layer-list track (`rgba(170,163,206,0.4)` / fill `#72E0B8`) + 16 dp flat thumb; drag-preview logic in `SeekBarController` (see D-flow §3). Everything else is plain views + shape drawables; **no Material Components** (flat look exact, APK lean, no elevation traps) — buttons are `TextView`/`ImageView` with `GradientDrawable`/selector backgrounds, ripple via `?attr/selectableItemBackground` (a ripple, not elevation, and cheap on this GPU).
- **Resources:** `colors.xml` (6 tokens + 5 literals from theme spec), `dimens.xml` (52dp min touch, 72/64 play, gaps, radii), `strings.xml` Spanish registry (verbatim list in Theme spec; plus corrected queue empty copy), `styles.xml` (`TextAppearance.Jc.*` ladder 30/24/20/18/16/14/12 wired to `res/font/fredoka_semibold.ttf`, `nunito_sans_regular.ttf`, `nunito_sans_bold.ttf`; `fontFamily` XMLs), `themes.xml` (`Theme.Julicuentos` parent `Theme.AppCompat.NoActionBar`, `windowBackground` = dark splash drawable, `statusBarColor` #17152E, `windowLightStatusBar` false). Vectors only static (no AVD) for transport icons.
- **`formatTime`/`formatRemaining`** as one pure object with JUnit tests (boundary table from Theme spec).

**Tradeoffs:** hand-rolled pill/chip/sheet instead of Material Components — a few hundred lines of drawable XML traded for no transitive theme/UI pulls and zero elevation surprises. Rejected: Material Components (drags in animation/elevation idioms that fight the flat audit); ConstraintLayout-free nesting (deeper hierarchies cost overdraw on this device); runtime layout branching (spec mandates qualifiers; also breaks on config change).

### D7 — Bitmap strategy

**Chosen:** keep the RN thumbnail/cover split, decode lazily, one small cache.

- Grid: `assets/thumbs/<id>.jpg` (512 px), decoded with `inSampleSize` to ≤ ~512 px (cells are 272–380 px), **`RGB_565`** (opaque JPEGs), cached in `LruCache` sized `min(12 MB, heap/8)` with key `t:<id>`; 20 thumbs ≈ ≤ 10 MB worst-case → far under budget even with all cells live.
- Player/miniplayer: `assets/covers/<id>.jpg` (up to 1400²) decoded **once per load** at ≤ 640 px, `RGB_565`, kept in a 2-entry cache (current + previous story for transitions); miniplayer reuses the player bitmap scaled by the view.
- No fade (`ImageView` default, no `animate()`), no largeHeap. Loader is a single-thread executor + main-thread post (no image library).
- Full-screen invalidation avoided: ring view redraws itself; images only re-decode on story change.

**Tradeoffs:** runtime decode (~ms per thumb on this SoC) vs pre-baked downscaled variants — rejected pre-baking because it doubles image payload or needs a build step for marginal win; 12 MB cache vs 8–16 MB spec window — picked middle, revisitable if the landscape fling shows pressure. Rejected: full-size cover decode in grid (the OOM the spec forbids), Glide/Coil (dependency weight + unknown-allocator behavior on Fire OS for zero benefit over 40 static assets).

### D8 — Audio focus, noisy, wake

**Chosen:** delegate to ExoPlayer — `player.setAudioAttributes(AudioAttributes(USAGE_MEDIA, CONTENT_TYPE_MUSIC), handleAudioFocus=true)`, `player.setHandleAudioBecomingNoisy(true)`, `player.setWakeMode(WAKE_MODE_LOCAL)`. On API 22 ExoPlayer's `AudioFocusManager` internally uses the legacy `AudioManager.requestAudioFocus(listener, STREAM_MUSIC, …)` API and implements exactly the spec's matrix: permanent loss → pause (no auto-resume), transient → pause + auto-resume, transient-can-duck → volume duck + restore. Headphone unplug → `AUDIO_BECOMING_NOISY` → pause (no speaker blast). Wake lock via `setWakeMode` (PARTIAL_WAKE_LOCK while playing) + the FGS keeps the process pinned.

**Tradeoffs:** focus semantics are library-owned (less code, well-tested paths); the cost is indirection when Fire OS misbehaves — mitigated by the device checklist explicitly re-verifying pause-on-loss and duck. Rejected: hand-rolled `AudioManager` focus plumbing (duplicates the library; the RN exploration shows exactly how such parallel implementations rot), `AlarmManager`-based timer (D3 uses in-process Handler + elapsedRealtime: the FGS + wake lock we already hold keeps the process alive during playback, which is the only time the timer needs to run — a minutes timer expiring while *paused* is a no-op by design).

### D9 — Build config

**Chosen (all pinned in `gradle/libs.versions.toml` + wrapper):**

| Thing | Version / setting |
|---|---|
| Gradle wrapper | **8.7** — dist seeded from the local `tools/gradle-dist/gradle-8.7` (host has no gradle CLI; see below) |
| AGP | **8.5.2** (min Gradle 8.7, JDK 17, compileSdk 34) |
| Kotlin | 1.9.24 (stdlib only) |
| compileSdk / minSdk / targetSdk | 34 / 22 / 34 |
| JDK | 17 (host-provided) |
| androidx.media3 (exoplayer, session) | 1.2.1 (minSdk 21; `media3-common`/`datasource`/`extractor` transitively) |
| androidx.appcompat | 1.6.1 (theme base + fragment/annotation transitives) |
| androidx.fragment | 1.6.2 |
| androidx.recyclerview | 1.3.2 |
| androidx.constraintlayout | 2.1.4 |
| androidx.core | 1.12.1 (NotificationCompat, pinned) |
| androidx.media | 1.7.0 (MediaStyle fallback per D2) |
| junit:junit | 4.13.2 (testImplementation, pure-JVM logic tests) |

- **No Material Components, no Compose, no coroutines, no Hilt, no DataStore, no Glide, no desugaring** (no java.time anywhere; time math is `SystemClock.elapsedRealtime` + long arithmetic). **`org.json`** (platform) for the two JSON spots — no serialization dependency.
- **Build types:** only `debug` is built/shipped; `minifyEnabled false` (no R8 — keep stack traces legible on-device; APK is asset-dominated so code shrinking saves ~1 %).
- **Assets:** `androidResources { noCompress += "mp3" }`; packaging keeps all 20 files STORED. AAPT2 links the ~650 MB APK fine (assets are STORED, not compiled); first `assembleDebug` will be slow (~minutes) — acceptable, and asset dirs are untouched between slices so incremental builds repackage but don't recompress. No `aapt2` overrides needed; `res/raw` avoided (id dashes).
- **Signing:** default debug keystore, `versionCode 1`, `versionName "1.0"`, `versionCode` bumped per installed slice (monotonic per delivery spec). Single APK, all ABIs (no native code at all).
- **Wrapper without a host gradle CLI:** commit the wrapper (jar+scripts) and set `distributionUrl` to a **local file URL** (`file:~/julicuentos-native/tools/gradle-dist/gradle-8.7-bin.zip`, produced once by zipping the existing unpacked dist — or a relative `../tools/...` resolved from `gradle/wrapper/`). First `./gradlew` run unpacks it into `GRADLE_USER_HOME`; `assembleDebug` then runs fully offline. Tasks slice 1 verifies this end-to-end (`./gradlew assembleDebug` green) — if the relative form misbehaves, fall back to the absolute file URL recorded in the repo README (host-specific, acceptable for a single-machine project; tradeoff documented).

**Tradeoffs:** Media3 1.2.x over 1.3.x — 1.2.1 is the longest-cooked line for API 21/22 and the spec set doesn't need 1.3+ features; upgrade is a version-bump if ever needed. Rejected: version-less `latest`, Gradle 8.9+/AGP 8.7 (requires newer dist than the host has), `largeHeap`.

### D10 — Review workload / slice forecast (input to tasks)

This change will exceed any single 400-line review budget by an order of magnitude once resources + 20 assets are counted. **Tasks phase MUST forecast chained PR slices; proposed decomposition (5 slices, each independently buildable):**

1. **Scaffold + spike** — wrapper/AGP/catalog pins, manifest, theme skeleton, one-asset spike build; `assembleDebug` green + device spike verdict.
2. **Catalog + assets pipeline** — port script, stories.json, images, `Catalog` parser + tests.
3. **Playback core** — service, ExoPlayer wiring, state machine, queue logic, ±15/next rules, focus, notification provider, timer logic + fade (pure logic tested in JVM).
4. **Catalog UI** — grid, cards, sheet, miniplayer, bitmaps.
5. **Player UI + queue UI + timer UI + persistence + polish** — this one is oversized; if it breaches the review budget, split 5a (player screens + seek) / 5b (queue + timer UI) / 6 (persistence wiring + restore + acceptance pass).

Each slice: green `assembleDebug`, `versionCode++`, installable. Tasks must forecast the chain and keep per-slice diffs reviewable (resource-only files count toward the budget).

---

## 3. Key data flows

### Load story (catalog tap → sound)

```
tap card D ──▶ CatalogFragment: resolveOpenStoryAction(D)
  ├─ D==current && Playing → open player only
  ├─ D==current && Paused  → repo.togglePlayPause(); open player
  └─ else → repo.playNow(D)
               ├─ state = Loading(D, pos=0)            ← UI paints immediately
               ├─ nav → PlayerFragment                 ← no await of prepare
               └─ controller.setMediaItem(item(D), 0); prepare(); playWhenReady=true
                    (service: old source torn down by setMediaItem before prepare)
                    player events (Ready/Playing/Error) land via listener;
                    stale-load events discarded via loadGen
```

### Seek gesture (drag-preview + commit-on-release)

```
onStartTrackingTouch → dragging=true; suppress progress listener writes to thumb
onProgressChanged    → thumb = finger; preview label = formatTime(ratio*realDuration)
                       (zero player calls; local assets make release-seek instant)
onStopTrackingTouch  → controller.seekTo(target)   ← exactly one call
while dragging: incoming positions update nothing but labels; on release the thumb
stays at finger position (no snap-back; RN's per-move seekTo is not ported)
```
Portrait layout keeps the bar out of any scroll container (fixed-height centered stack on the Fire), so no gesture theft exists at the platform level.

### Timer expiry (minutes mode)

```
SleepTimer(Handler, 1 Hz, service main looper; anchored endsAt-elapsedRealtime):
  remaining>0 → publish remaining (UI 1 Hz line, ceil format)
  remaining≤0 → FADE: 10 steps × 1 s, player.volume 1.0→0.0 (stepwise, API-22-legal)
              → pause(); volume=1.0; timer={off}; store.flush() (immediate)
              → UI: timer line + peach ring gone; queue untouched
cancel rules: user pause/play/timer change or focus loss mid-fade → cancel fade, volume=1.0, timer=off
end_of_story: onEnded → pause, timer=off, NO advance (queue untouched)
```

### Cold restore (success criterion c)

```
launch → MainActivity → CatalogFragment
  └─ PlaybackRepository.connect(): MediaController Future
       └─ onConnected: read prefs (one key) → parse tolerantly
            ├─ userMutated? (any play/pause/playNow/queue tap already fired)
            │     └─ discard snapshot; keep live state          [guard 2]
            ├─ unknown currentStoryId → Idle (no miniplayer); unknown queue ids filtered
            ├─ expired/invalid timer → off; story+position+queue restore normally
            └─ else: prepare(current, positionMs, autoplay=FALSE) → state Ready(paused)
                     timer restored with elapsedRealtime window check
                     hydrationComplete=true → flushes may resume
```
Writes are impossible before `hydrationComplete` (persistence layer returns early — the historical blank-queue bug).

---

## 4. Asset & catalog pipeline (build-time, Kotlin-independent)

```
RN repo (read-only)                        native repo
content/cuentos.tsv ────┐
content/<id>/cover.jpg ─┤   tools/port-catalog.py (stdlib)
content/<id>/thumbnail.jpg ─┤
                        └──┬─► app/src/main/assets/stories.json   (20 entries, alphabetical)
                           ├─► app/src/main/assets/covers/<id>.jpg      (player/miniplayer)
                           ├─► app/src/main/assets/thumbs/<id>.jpg      (512 px grid)
                           └─► validation report: 20/20 audio+covers+thumbs exist,
                               catalog duration vs real MP3 duration ±2 s (warn/fail)
content fixes inline in script: bambi synopsis, "Los Increíbles", drop fechaPublicacion
```
App side: `CatalogParser` (org.json) validates every entry, drops bad ones, sorts by id, exposes `byId: Map<String, Story>`; parse runs on a worker thread at first `Catalog` touch, before any persistence hydration (restore needs `byId` to drop unknown ids — ordering is: catalog parsed → hydrate → UI).

`stories.json` lives in git (small); the 20 MP3s (~650 MB) are already in `app/src/main/assets/audio/` and must be kept **out of review diffs** (binary; see risks). Covers/thumbnails are ported by the script (center-crop square policy inherited from the RN generator; thumbnails already 512 px in source).

---

## 5. Manifest, permissions, packaging

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/> <!-- no-op on 22 -->
<!-- INTERNET: absent, by design -->
<application android:allowBackup="false" android:theme="@style/Theme.Julicuentos" …>
  <activity MainActivity exported launcher/>
  <service .playback.PlaybackService exported="true" foregroundServiceType="mediaPlayback">
    <intent-filter><action MEDIA_PLAYBACK/></intent-filter>
  </service>
  <receiver androidx.media3.session.MediaButtonReceiver exported MEDIA_BUTTON/>  <!-- hardware keys / restart on 22 -->
</application>
```
No `INTERNET`; `aapt dump permissions` is an acceptance check. `noCompress "mp3"` verified by `unzip -v` (STORED). Debug-signed; `adb install -r`; documented `--no-local` fallback.

---

## 6. Project file tree (sketch)

```
julicuentos-native/
├── settings.gradle.kts · build.gradle.kts · gradle.properties
├── gradle/libs.versions.toml · gradle/wrapper/* · gradlew
├── app/
│   ├── build.gradle.kts                # minSdk 22, compileSdk 34, noCompress mp3, debug signing
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/ audio/*.mp3 (20) · covers/ · thumbs/ · stories.json
│       ├── java/com/julicuentos/app/
│       │   ├── MainActivity.kt
│       │   ├── catalog/ Story.kt Catalog.kt CatalogParser.kt
│       │   ├── playback/ PlaybackService.kt PlaybackRepository.kt PlayerState.kt
│       │   │            QueueLogic.kt SleepTimer.kt Fade.kt MediaItemFactory.kt
│       │   │            AudioSourceResolver.kt NextResolver.kt
│       │   ├── persist/ PersistedState.kt PlayerStore.kt RestoreCoordinator.kt
│       │   ├── media/ Bitmaps.kt ThumbCache.kt
│       │   └── ui/ catalog/(CatalogFragment, StoryAdapter, StoryActionSheet, MiniPlayerView)
│       │           player/(PlayerFragment, CoverHaloView, SeekBarController)
│       │           queue/(QueueFragment, QueueAdapter) timer/TimerFragment common/(TimeFormat…)
│       └── res/ layout/ layout-w600dp-land/ values{,-land,-w768dp,-w1024dp,-land-h720dp}/
│              font/ drawable/ mipmap/ color/
├── app/src/test/java/… (QueueLogic, TimerLogic, PersistedState, RestoreCoordinator, TimeFormat)
├── tools/ port-catalog.py · download-audio.sh · gradle-dist/
└── openspec/changes/migrate-to-native-android/…
```

---

## 7. Risks (design-level, beyond the proposal's table)

| Risk | Design response |
|---|---|
| AssetDirect fails on Fire kernel despite STORED entries | Spike is slice 1; `AudioSourceResolver` seam makes the copy-to-filesDir fallback a ~1-day change with zero player/UI churn |
| Media3 default notification quirks pre-O | We own the notification via a custom provider (D2); failure mode degrades to a plain notification, never to lost playback |
| aapt2/package step with ~650 MB uncompressed assets (build time / disk) | Build once in slice 1 to measure; assets are write-once (not touched between slices); CI-less host so cost is one-time per build |
| Fragment rotation churn re-binding progress | All progress widgets re-subscribe on `onStart` from the repository snapshot; no per-frame work; orientation change is a full layout re-inflate by design |
| 650 MB of binaries in git | Design keeps MP3s out of review surface; tasks decide `.gitignore` vs LFS policy (audio-download.log/.pid already transient at repo root — do not commit) |
| Kotlin/AGP emitting API-22 bytecode with modern toolchain | Pin AGP 8.5.2 / Kotlin 1.9.24 / JDK 17 (all API-21-min compatible); desugaring explicitly off; slice 1 proves `assembleDebug` on-device-installable |
| `stories.json`/TSV drift (durations off by seconds) | Port script asserts 20/20 files + duration probe report; app treats catalog duration as placeholder only (Playback spec) |

---

## 8. Spec → design traceability (fast index)

## 9. API 22 compatibility audit (parent-verified, 2026-08-30)

Every dependency and runtime API used by this design was checked against Android 5.1.1 (API 22):

| Item | Version | minSdk | API 22 verdict |
|---|---|---|---|
| androidx.media3 (exoplayer, session) | 1.2.1 | 21 | OK — and **verified safe**: Media3 **1.3.0+ requires core library desugaring** and crashes on API 22 with `NoSuchMethodError: List.stream()` (androidx/media#1379). Our pin to 1.2.1 + "no desugaring" build decision is load-bearing; do not bump Media3 past 1.2.x without enabling desugaring. |
| androidx.appcompat | 1.6.1 | 14 | OK |
| androidx.fragment | 1.6.2 | 14 | OK |
| androidx.recyclerview | 1.3.2 | 14 | OK |
| androidx.constraintlayout | 2.1.4 | 14 | OK |
| androidx.core | 1.12.x | 14 | OK — pin **1.12.0** if `1.12.1` does not resolve on Maven (verify at first build; Gradle fails fast) |
| androidx.media (MediaStyle) | 1.7.0 | 14 | OK |
| Kotlin stdlib | 1.9.24 | — | OK (JVM 1.8 target; no java.time/streams on runtime paths) |
| AGP | 8.5.2 | — | OK (emits API 22 bytecode; needs Gradle 8.7 + JDK 17 — both present on host) |
| Runtime APIs | `SystemClock.elapsedRealtime()`, legacy `AudioManager` focus (via ExoPlayer AudioFocusManager), `NotificationChannel` O-guarded, `setWakeMode`, `LruCache`, `RGB_565`, ripple (`?attr/selectableItemBackground`, API 21+), `ResourcesCompat.getFont` (API 16+), legacy PNG mipmaps (no adaptive icons on 22) | — | OK |
| Forbidden APIs | `java.time`, `List.stream()`, `AudioFocusRequest` (26+), adaptive icons, `RenderEffect`/blur, animated vectors | — | not used ✓ |

Build-time resolution is the final arbiter: slice 1's `assembleDebug` + device spike validate this table end-to-end.


| Spec area | Design answer |
|---|---|
| Background playback ≥ 30 min (a) | D2/D8: FGS `mediaPlayback` + Media3 wake mode + MediaStyle notification; device-verified |
| Seek contract (b) | Flow §3 seek; custom logic in `SeekBarController`; no scroll container |
| Cold restore paused (c) | §3 restore flow + D3 + persistence guards (never-write-before-hydration, user-wins) |
| Play-now keeps queue | `resolveOpenStoryAction` + `QueueLogic` (pure, tested); no `playNowClearsQueue` |
| Timer fade + screen-off (deltas) | D3 + service-owned `SleepTimer`/fade; elapsedRealtime anchor; persisted `endsAt` window |
| Notification ±15/next + artwork | D2 custom MediaStyle provider, actions via session commands |
| Duration from real metadata | ExoPlayer `duration` post-prepare overrides catalog chip placeholder |
| Flat palette/fonts/52 dp | D6 resource strategy; audit in tasks acceptance |
| Grid 5/3/2 + OOM budget | D6 integer qualifiers + D7 bitmap pipeline |
| Build/permissions/no INTERNET | D9 + §5; aapt verification in slice 1 |
