# Apply Progress — migrate-to-native-android

## Slice 1 — Scaffold + device spike (implemented 2026-08-30)

Status: **DONE (host-verifiable scope)**. `./gradlew assembleDebug` green on the first run;
on-device spike items deferred to the next Fire HD 10 session (see Deferred below).

### What was done

1. Gradle wrapper bootstrapped from the local distribution
   (`tools/gradle-dist/gradle-8.7/bin/gradle wrapper --gradle-version 8.7`), then
   `distributionUrl` pointed at the local zip. `./gradlew --version` verified
   (Gradle 8.7, JVM 17.0.19 Zulu, no network needed for the distribution).
   Bootstrap notes: Gradle 8.7 refuses the `wrapper` task without a settings file,
   so `settings.gradle.kts`/`gradle/libs.versions.toml` were written first; the dist
   zip was renamed `gradle.zip` → `gradle-8.7-bin.zip` to match the spec'd URL.
2. Root build files + version catalog pins per design D9.
3. App module skeleton: manifest (4 permissions, no INTERNET), `JulicuentosApp`,
   `MainActivity` stub (TextView "Julicuentos — Slice 1"), colors/themes/strings.
4. Fonts copied from the RN repo: `fredoka_semibold.ttf` (Fredoka_600SemiBold),
   `nunitosans_regular.ttf` (NunitoSans_400Regular), `nunitosans_bold.ttf`
   (NunitoSans_700Bold). fontFamily XMLs + TextAppearance ladder are NOT part of
   the slice-1 delegation (see Deviations).
5. Launcher mipmaps composited with PIL: RN `android-icon-foreground.png`
   (1024² RGBA) over #17152E, foreground at ~66 % center, exported as
   `ic_launcher.png` at mdpi 48 / hdpi 72 / xhdpi 96 / xxhdpi 144 (no adaptive
   icons — API 22 target).
6. `tools/port-catalog.py` (stdlib only) ported the catalog: 20/20 entries in
   `stories.json` (alphabetical, validator-checked, round-trip verified),
   20 covers + 20 thumbnails copied to `assets/covers/<id>/`. Content fixes
   applied and output-validated (assertions added post-review, R1-002): bambi "ambi es..." → "Bambi es...", "Los Increibles" →
   "Los Increíbles", whitespace stripped, `fechaPublicacion` dropped.
7. Build gate + APK verification (see evidence).
8. tasks.md updated: S1.1–S1.4, S1.8, S1.9, DV2 checked; spike/gate tasks
   annotated `deferred-device`.

### Files created

```
julicuentos-native/
├── settings.gradle.kts                  pluginManagement repos, :app include
├── build.gradle.kts                     pinned plugin declarations (aliases)
├── gradle.properties                    useAndroidX, 4g daemon heap, UTF-8
├── gradle/
│   ├── libs.versions.toml               all D9 pins (agp, kotlin, media3, androidx...)
│   └── wrapper/{gradle-wrapper.jar, gradle-wrapper.properties}
├── gradlew · gradlew.bat                distributionUrl=file:/.../tools/gradle-dist/gradle-8.7-bin.zip
├── app/
│   ├── build.gradle.kts                 minSdk 22, compileSdk/targetSdk 34, noCompress mp3
│   └── src/main/
│       ├── AndroidManifest.xml          4 permissions, no INTERNET, launcher activity
│       ├── java/com/julicuentos/app/
│       │   ├── JulicuentosApp.kt        Application stub
│       │   └── MainActivity.kt          slice-1 placeholder screen
│       ├── assets/
│       │   ├── audio/                   (pre-existing, gitignored, untouched)
│       │   ├── covers/<id>/cover.jpg + thumbnail.jpg   (20×2 files, 27 MB)
│       │   └── stories.json             20 entries, alphabetical
│       └── res/
│           ├── font/{fredoka_semibold,nunitosans_regular,nunitosans_bold}.ttf
│           ├── mipmap-{mdpi,hdpi,xhdpi,xxhdpi}/ic_launcher.png
│           └── values/{colors,strings,themes}.xml
└── tools/port-catalog.py                re-runnable catalog port script
```

### Build evidence

- Gate command: `./gradlew assembleDebug` (first run, deps fetched once)
- Exact success line: **`BUILD SUCCESSFUL in 51s`** — `35 actionable tasks: 35 executed`
- APK: `app/build/outputs/apk/debug/app-debug.apk` — **721,575,153 bytes (~688 MiB)**
- Badging: `package: name='com.julicuentos.app' versionCode='1' versionName='1.0'`,
  `sdkVersion:'22'`, `targetSdkVersion:'34'`, compileSdk 34, label `Julicuentos`
- Permissions (`aapt dump permissions`, build-tools 34.0.0): exactly
  FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, WAKE_LOCK,
  POST_NOTIFICATIONS — **no INTERNET**. (Plus androidx.core's auto-generated
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; see note in Deviations.)
- MP3 packaging: `unzip -v` shows all **20** `assets/audio/*.mp3` entries **Stored**
  (0 % compression) — `noCompress "mp3"` working.
- Catalog validation: 20 entries, ids unique + alphabetical (ASCII order == Java
  `String.compareTo` for the `^[a-z0-9-]+$` id charset), all 40 image paths exist,
  JSON round-trip equal; bambi/Los Increíbles fixes asserted via validate() (added post-review, R1-002).
- Audio integrity: 20 MP3s confirmed on disk and none staged in git
  (`.gitignore` covers `app/src/main/assets/audio/`, `audio-download.log`,
  `audio-download.pid`); file contents untouched per delegation.

### Deferred (device) items

| Item | Why deferred |
|---|---|
| S1.10 on-device spike build (single-asset play-button APK) | Superseded in part: full-catalog APK is green, all 20 entries verified STORED, APK size recorded. On-device play/seek/screen-off checks need the Fire HD 10. |
| S1.11 spike verdict (start <2 s, seek 46:00/51:00, ENDED, screen-off +600 s, logcat clean) | Needs Fire HD 10 session; verdict gates slice 2 (D1 fallback decision). |
| S1.12 `adb install -r` OK | Needs device. `versionCode` stays 1. |
| DV1 | Same as S1.11. |

### Deviations from design (each flagged)

1. **`distributionUrl` uses the absolute `file:` URL** (delegation instruction)
   instead of design D9's preferred relative `file:../tools/...`. D9 pre-authorized
   the absolute form as a documented fallback for this single-machine project.
   Also: the dist zip was renamed `gradle.zip` → `gradle-8.7-bin.zip` so the URL
   matches the wrapper dist name.
2. **Plugin versions declared in root `build.gradle.kts`, not the settings
   plugins block.** Gradle 8.7 cannot resolve version-catalog aliases from the
   settings plugins block (`Unresolved reference: libs`; `versionCatalogs` is not
   part of `pluginManagement` in the Kotlin DSL). Pins remain single-sourced in
   `gradle/libs.versions.toml`.
3. **androidx.core pinned `1.12.0`** (delegation) vs tasks S1.2's `1.12.1`-first
   pin. Resolved cleanly at 1.12.0; the 1.12.1 fallback was not needed.
4. **Cover/thumbnail asset layout** = `assets/covers/<id>/cover.jpg` +
   `assets/covers/<id>/thumbnail.jpg` (delegation) instead of design §4's
   `covers/<id>.jpg` + separate `thumbs/<id>.jpg`. `stories.json` carries explicit
   `cover`/`thumbnail` path fields, so slice 3's bitmap pipeline follows the JSON
   and no consumer code hardcodes either layout.
5. **Duration probe (±2 s vs MP3) not run** — the audio directory was declared
   off-limits for this slice (do not touch or list). Catalog durations remain
   placeholders by design (specs/playback: real metadata wins post-prepare).
6. **S1.5/S1.6/S1.7 partially scoped**: `styles.xml` TextAppearance ladder,
   fontFamily XMLs, dimens and the dark splash drawable were NOT in the slice-1
   delegation and are left unchecked in tasks.md with `partial` annotations;
   they land with the UI slices. Slice 1 ships theme/colors/strings base +
   fonts + mipmaps only.
7. **Merged-manifest permission note**: androidx.core 1.12 auto-adds the
   app-defined `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (normal-protection,
   no network). The delivery spec's "exactly {4 permissions}" aapt check should
   account for this inherent library addition when DV2/acceptance runs.
8. **strings.xml base set**: the theme-spec registry has no separate catalog
   header title, so `catalog_title` = "Julicuentos" (verbatim RN copy);
   `play`/`pause`/`next` base labels ("Reproducir"/"Pausar"/"Siguiente") are
   slice-1 placeholders to be reconciled with the verbatim registry pass in S5.8.
   The slice-1 placeholder string `slice1_placeholder` is dev-only and removed
   in slice 3.

### Next slice readiness

Slice 2 (catalog data + playback core) can start once the S1.11 spike verdict is
recorded on the Fire HD 10 (tasks: "Spike verdict gates slice 2"). Everything it
needs from the scaffold is in place: pinned toolchain, green build, catalog asset
format (JSON-carried paths), manifest permission set, and the `noCompress`
STORED-entry guarantee the `AudioSourceResolver` seam relies on.

## Slice 2 — Kotlin playback core (no UI)

Shipped the slice-2 delegation: catalog repository, audio seam, media session
service with a single ExoPlayer, custom media notification provider, media-controller
repository bridging to a 7-state machine, manifest declarations. **Build gate
green**: `./gradlew assembleDebug` on host, APK generated, `versionCode` bumped 1 → 2.

### What landed

- `catalog/Story.kt`, `catalog/CatalogParser.kt`, `catalog/StoryRepository.kt`:
  process-scoped singleton loading `assets/stories.json` once via org.json with validator-first
  pass (skip entries missing/malformed `id`/`titulo`, coerce `duracionSegundos` ≥ 0),
  alphabetical `all()` list + `getById`.
- `playback/AudioSourceResolver.kt`, design D1 seam: primary
  `asset:///audio/<id>.mp3`, fallback copy-to-filesDir strategy **behind a flag** since the
  spike verdict is deferred-device: default strategy stays ASSET_DIRECT and flipping the
  flag later requires no other code change. Missing audio file → `null` → error state,
  never a crash.

### Deviations from the slice-2 spec wording

- **D1 strategy gate deferred by device**, not implemented conditionally: the seam
  carries the strategy parameter now, verdict recordland with DV1. Copy-to-files
  code and its `.done` marker logic exist behind `Strategy.COPY_TO_FILES` but are not
  exercised by default this slice.
- **`MediaSession.Callback` is an interface** in media3 1.2.1: kotlin
  `object : MediaSession.Callback { ... }` — no constructor parens. The only ExoPlayer
  build uses `C.WAKE_MODE_LOCAL` and the audio-attribute triple as pinned in design D8.
- **`androidx.core.os.MainThreadExecutor` does not exist** in androidx.core 1.12:
  the controller future listener uses `ContextCompat.getMainExecutor(context)` (main looper)
as required by the MediaController java docs on API  ️22 concurrency.

- `playback/PlaybackService.kt` + manifest: `MediaSessionService` subclass owning the
  single `ExoPlayer` (audio attributes, `handleAudioFocus=true`, `handleAudioBecomingNoisy=true`,
  `WAKE_MODE_LOCAL`), custom session commands SKIP_BACK_15 / SKIP_FWD_15 (design D2),
  `onTaskRemoved` stop-if-not-playing. Manifest declares the service exported with
  `foregroundServiceType="mediaPlayback"` and the canonical
  `androidx.media3.session.MediaButtonReceiver` for API-22 media-button intents.

  NOTE the manifest's service intent-filter action is the canonical
  `androidx.media3.session.MediaSessionService`; design §5's "MEDIA_PLAYBACK_SERVICE"
  wording is resolved to the real media3 constant, not a custom string. Also complies
  with the delivery-spec 4-permission set (no INTERNET, ever).
- `notify/MediaNotificationProvider.kt`: custom provider (NOT DefaultMediaNotificationProvider),
  `NotificationCompat.MediaStyle`, channel "playback" only on SDK_INT ≥ 26,
  actions play/pause (`Player.COMMAND_PLAY_PAUSE`), −15 s, +15 s, next
  (compact shows the first three, next expanded-only), `largeIcon` downsampled
  ≤ 256 px RGB_565 cover via the shared `BitmapDecoder`. Notification labels are
  Spanish (strings.xml additions): "Reproducción", "Reproducir / Pausa",
  "Retroceder 15 s", "Avanzar 15 s", "Siguiente".
- `playback/PlayerState.kt` + `QueueStore.kt`: sealed 6-state/Error machine
  (`Idle/Loading/Ready/Playing/Paused/Ended/Error`) and the queue seam (`QueueStore`
  interface + in-memory-empty impl now; full queue is slice 5).. Notification large-icon
  accepts Bitmap directly (androidx.core overloads ere Bitmap | Icon; IconCompat not one),
  so no IconCompat conversion appears in the provider. That was a compile-time fix
  recorded here for future slices touching notification artwork.

- `playback/PlaybackRepository.kt`: process-scoped singleton bridging UI ↔ service via a
  single `MediaController` (async `buildAsync()`, await-contract via the future's
  listener on the main looper; no coroutines by design). Seven-state `PlayerState`
  derivation, 500 ms main-Handler progress ticks only while
  Ready/Playing/Paused, `load(storyId,pos,autoplay)` with a monotonic `loadGen`
  stale-guard, play/pause/toggle/seekTo/skipBy(clamped), `playNext()` queue-head
  else circular-catalog-next. ENDED-handling maps to `Ended` + stop when queue empty;
  missing-audio surfaces `PlayerState.Error`, no crash. Load-pending
  parked and replayed once the controller connects (deferredLoad).

### Slice-2 acceptance-status

- Build gate green on host: `./gradlew assembleDebug` (no errors, APK laid down,
  `versionCode=2` reflected in app/build.gradle.kts.
 No commit per slice-2 rule;
  parent reviews. Device-dependent items (notification interactivity, screen-off playback,
  media-button wiring on Fire HD API  ️22) stay unchecked with `deferred-device`
  annotations in tasks.md an resolve with DV1/DV3/DV4 acceptance runs.[x] marks cover
  only the host-verifiable/Kotlin-core scope derived from the slice-2 delegation. JUnit
  stubs whethe spec named per-task tests are deferred to the test slice by design;
  none shipped here (repository declares no sources of truth for tests yet).

### Next slice readiness

Slice 3 (catalog UI),4 (player UI) can build on this core directly: the
repository/provider/service API surface is setted and green. The D1 spike verdict recorded
in DV1 gates the initial `AudioSourceResolver.Strategy` default; the flag
already lives behind the seam, so no refactor is needed when the verdict flips.

## Slice 3 — Catalog UI

Implemented the slice-3 delegation: single-activity fragment nav, catalog grid + cards,
story adapter with targeted payload pill, bitmap pipeline, action sheet, tap semantics,
in-flow miniplayer, versionCode bumped 2 → 3. **`assembleDebug` green.**

### What landed

- S3.1 `MainActivity` = fragment host (`supportFragmentManager` back stack, no nav library, no
  nav args per design D5): Catalog opens first (no back-stack entry(; Player/Queue/Timer stubs
  push entries(. Stub screens show branded titles + "Volver al catálogo" (back pops( stone Slice comp walk.
- S3.2 layouts: `fragment_catalog.xml` (header "Julicuentos" + subtitle, grid, in-flow mini footer(;
  `item_story_card.xml` (ConstraintLayout: square thumbnail, "Sonando" pill bottom-left, duration chip
  bottom-right overlay, Fredoka 16 sp title (2 lines(, Nunito 14 sp synopsis (2 lines((,
  52 dp ⋮ overflow(. Columns via `values/integers.xml`=2,`values-w768dp`=3,`values-w1024dp`=5
  → `GridLayoutManager(span)` (integer qualifiers per D6(.
- S3.3 `StoryAdapter`: bind-once cells with stable ids( story-id hash(;pill moves via
  `notifyItemChanged(position, PAYLOAD_CURRENT)` on current-story change only — grid cells never
  subscribe to the 500 ms progress cadance(.
- S3.4 `media/Bitmaps.kt` (shared executor + 2-entry cover cache ≤640 px( + `media/ThumbCache.kt`
  (`LruCache` min(12 MB, heap/8), thumbs ≤512 px RGB_565 via inSampleSize( reused the existing
  `BitmapDecoder` primitive; all images load off-main and post to the main looper.
- S3.5 `StoryActionSheet` DialogFragment bottom sheet: title (Fredoka 18 sp), "Reproducir ahora"
  (solid mint(,"Añadir a la cola" (mint outline( — routes to `repository.enqueue(storyId)` —,Cancelar;
  53 rows ≥52 dp with 16 dp corners; dim scrim via the dialog theme. The enqueue/clear hooks are MINIMAL
  (append+de-dup( added to `QueueStore` now per the brief; full reorder/remove/persistence logic
  remains slice-5/S2.2 (noted below(.
- S3.6 Tap semantics (`resolveOpenStoryAction`) per spec: playing-current → open only;paused-current →
  toggle+open;else → `load(id,0,autoplay=true)` + open immediately. **Play-now preserves the queue**
  (behavior-change marker( — `load` never touches the queue(.
- S3.7 `MiniPlayerView` in-flow footer ( not overlay(: 48 dp cover, 1-line title, status line, 52 dp
  mint rounded-square play/pause (corner ratio 0.22 ≈ 12 dp), 52 dp next (`repo.playNext())`,
  non-interactive `MiniProgressStrip` (track #3D3860/fill #72E0B8, 6 dp( at the top; hidden entirely when
  `currentStory == null`;bar tap → PlayerFragment;subscribes to progress (strip + status( only.
- S1.5/S1.6 closure: `res/font/fredoka.xml` + `res/font/nunitosans.xml` fontFamily XMLs,
  `values/styles.xml` `TextAppearance.Jc.*` ladder (30/24/20/18/16/14/12(, `values/dimens.xml`,
  and the dark splash resolved by the color-based `@color/fondo` windowBackground (no white flash( —
  marked [x] in tasks.md. Also added `implementation(libs.androidx.constraintlayout)` and
  `implementation(libs.androidx.recyclerview)` to `app/build.gradle.kts` (pins already existed in the toml容器.

### Build evidence

- Gate: `./gradlew assembleDebug` green ( BUILD SUCCESSFUL; APK
  `app/build/outputs/apk/debug/app-debug.apk` currently ~1.25 GB — **see asset anomaly
  under Deviations; the ~688 MB + 20-asset baseline from slices 1–2 multiplied** —
  versionCode=3 reflected in `app/build.gradle.kts`.
- Permissions/manifest unchanged ( still no INTERNET(.

### Deferred (device items

- S3.8 on-device checks: column counts at 2/3/5 buckets, 5-col landscape fling
  without OOM, pill tracking, miniplayer strip animation — assigned to DV9.
- Notification/player/miniplayer interaction testing stays with the device sessions (DV1/DV3/DV4/gates(.

### Deviations and risks

1. **Extraneous asset tree (UNTRACKED + modified(**: the repo now contains ~44 extra MP3s in
   `assets/audio/` and ~72 extra cover dirs under `assets/covers/` NOT in `stories.json`,و AND the 20
   tracked covers show as Modified( — all PREDATE/EXTERNAL to THIS slice( the parent должен
   decide: keep/ignore/gitignore-patterns before committing. My slice touched no assets(. The APK sized
   grows accordingly (~1.25 GB vs ~688 MB( — flagging rather than deleting(.
2. `android:windowDimAmount` removed from `Theme.Julicuentos.Dialog` — AAPT2 refused the attr
   (style attribute not found( on AGP 8.5.2; default dialog dim (~0.6( approximates the
   spec scrim 0.5( and `android:backgroundDimEnabled=true` retained(.
3. `QueueStore` gained minimal `enqueue`+`clear` (+ de-dup append( this slice per S3.5 brief;
   full queue semantics (moveUp/moveDown/remove/persistence( и the queue UI remain slice-5/S2.2.
4. `formatDuration` ( m:ss( is a private helper in `StoryAdapter`;the shared pure `TimeFormat`
   object lands in S4.6 ( flagged(andel the helper will be hoisted there.
5. Theme attribute `android:enabled=false` used for `MiniProgressStrip` — `isEnabled` isthe getter,
   not an XML attr(.
6. The bitmaps pipeline reuses the existing `BitmapDecoder` ( no duplicated decode logic(.

### Next slice readiness

Slice 4 (player UI) can build directly: the catalog grid, miniplayer, action sheet and
stub nav are wired;the player layouts with seek/transport land next slice.

## Slice 4 (recovered)

Recovery of the player-UI slice after a git history rewrite reset the
tracked-modified slice-4 files to HEAD while the untracked files survived.

Status: **DONE (host-verifiable scope)** — `assembleDebug` and
`testDebugUnitTest` green; on-device seek-contract checks (DV5) deferred.

### What was lost to the history-rewrite checkout

- `app/src/main/res/values/dimens.xml` — the `player_*` block (16 dimens
  referenced by the surviving player layouts) had been reverted to the
  slice-3 HEAD set.
- `app/src/main/res/values/integers.xml` — `player_desc_max_lines` (portrait
  default 2) missing; the portrait layout references it and AAPT2 would fail
  without it.
- `app/src/main/res/values/strings.xml` — player strings (`cargando_audio`,
  `cerrar`, `cerrar_reproductor`, `no_hay_cuento`, `seek_bar`, `ver_cola`,
  `reintentar`) reverted; layouts reference all of them.
- `app/src/main/java/com/julicuentos/app/ui/player/PlayerFragment.kt` — reset
  to the slice-3 placeholder stub (31 lines, fragment_placeholder).
- `app/src/main/java/com/julicuentos/app/ui/catalog/StoryAdapter.kt` — still
  carried the private `formatDuration` helper instead of the hoisted
  `common/TimeFormat` (slice-2 apply-progress deviation 4 was open again).
- `app/build.gradle.kts` — `versionCode` reverted 4 → 3.

### Survived (untracked, treated as fixed contracts)

- `common/TimeFormat.kt` + `TimeFormatTest.kt`, `ui/player/CoverHaloView.kt`,
  `ui/player/SeekBarController.kt`, both `fragment_player.xml` layouts,
  `values-land/` + `values-h720dp-land/` qualifier dirs, and the six
  drawables (`bg_play_circle`, `bg_seek_thumb`, `bg_seekbar_track`, `ic_add`,
  `ic_close`, `ic_timer`).

### What was rebuilt

- `values/dimens.xml`: added the 16 `player_*` dimens (base = portrait roomy:
  sectionGap 24, playSize 72, bottomPadding 32, cover 340dp cap, top bar 56dp,
  transport icon 52dp, play inner icon 34dp, land gap 28, controls width 520dp,
  transport gap 8, desc/seek-labels gap 8, pill top gap 24 + padding h 20,
  timer line gap 24). `min_touch` 52dp already present. Landscape compact
  (12/64/16) stays in `values-land/`, roomy restore in `values-h720dp-land/`.
- `values/integers.xml`: `player_desc_max_lines` = 2 (portrait default; the
  landscape qualifier files already carry 1 / 2).
- `values/strings.xml`: added `cargando_audio`, `cerrar`, `cerrar_reproductor`,
  `no_hay_cuento`, `seek_bar`, `ver_cola`, `reintentar`; removed
  `player_stub_subtitle` (no longer referenced after the PlayerFragment
  rewrite). Existing strings untouched.
- `ui/player/PlayerFragment.kt`: full rewrite per specs/playback + surviving
  layouts/controllers (see Diff highlights below).
- `ui/catalog/StoryAdapter.kt`: `formatDuration` replaced by
  `TimeFormat.formatTime(sec * 1000)` (slice-2 deviation 4 closed for real).
- `app/build.gradle.kts`: `versionCode` 3 → 4 (gate value, not bumped further).
- tasks.md: S4.1–S4.8 marked [x]; S4.8 annotated `deferred-device`.

### PlayerFragment diff highlights (recovered behavior)

- Binds the repository snapshot (`state`, `currentStory`, progress ticks); adds
  state + progress listeners in `onStart`, removes in `onStop` + `onDestroyView`
  (design D5 re-subscribe rule, no leaks).
- `CoverHaloView` progress ratio on each tick, `setTimerActive(false)` (slice 5
  wires the real timer state).
- `SeekBarController` attached once; commit callback = `repo.seekTo`; preview
  label comes from the controller (`formatTime(ratio * realDuration)`); zero
  player calls while dragging.
- Transport: timer → `openTimer()`, −15 s / +15 s via `repo.skipBy`, play/pause
  toggle with glyph swap (ic_pause_dark / ic_play_dark + contentDescription),
  ＋ → `repo.enqueue(current.id)` (append + de-dup inside QueueStore),
  "Ver cola" → `openQueue()`; back ✕ / empty "Volver al catálogo" / error
  "Cerrar" → `popBackStack()`.
- States: Loading shows the mint "Cargando audio…" line over content; Error
  shows message + Reintentar (relaunch `load(current, lastPos, autoplay=true)`)
  + Cerrar; Idle shows "No hay ningún cuento…" overlay.
- Duration: catalog seconds as placeholder, replaced by real metadata on the
  first progress snapshot (S4.7).

### Build evidence

- Gate 1: `./gradlew assembleDebug` → **BUILD SUCCESSFUL in 6s**
  (incremental; 15 executed / 20 up-to-date).
- Gate 2: `./gradlew testDebugUnitTest` → **BUILD SUCCESSFUL in 1s**;
  `TimeFormatTest` 5 tests / 0 failures / 0 errors.
- APK: `app/build/outputs/apk/debug/app-debug.apk` (~1.25 GB, asset-dominated —
  unchanged vs slice 3; assets untouched). `versionCode=4` in
  `app/build.gradle.kts` (not bumped further).
- Preexisting warnings only: `adapterPosition` deprecation in StoryAdapter
  (tracked before this slice; out of scope).

### Deviations and risks

1. `ver_cola` and `reintentar` were added to strings.xml beyond the five listed
   in the brief — both are referenced by the surviving layouts ("Ver cola" pill,
   error-card Reintentar) and the theme-spec registry, so the build required them.
2. `player_desc_max_lines` was restored in `values/integers.xml` (portrait = 2);
   the landscape qualifier integers (1 / 2) survived and were left untouched.
3. `values/dimens.xml` base carries the full roomy `player_*` set; the surviving
   `values-land/` (compact 12/64/16) and `values-h720dp-land/` (roomy restore)
   files were NOT modified — every other player dimen falls through to the base.
4. PlayerFragment error card shows the repository's message verbatim (library
   wording for missing-audio, e.g. "Audio file missing for <id>"); the Spanish
   error copy belongs to the repository surface, which is out of scope for this
   recovery (spec assigns the copy to the UI slice, but the repository is
   tracked/committed and untouched).
5. `player_timer_line` and `halo.setTimerActive` stay inert until slice 5 wires
   the sleep timer; the layouts declare the line GONE by default.

## Slice 5 (completed after agent crash)

Status: **DONE on the host (build + unit-test scope)**; on-device acceptance
items remain pending (DV1–DV11 minus host-verified DV2; S5.10 deferred-device).


### Core-by-previous-agent inventory (verified present, not rewritten)

1. `persist/` (MiniJson.kt, PersistedState.kt, PlayerStore.kt,
   RestoreCoordinator.kt) + tests `persist/PersistedStateParserTest.kt`,
   `persist/RestoreCoordinatorTest.kt` — one SharedPreferences key, tolerant
   parser, restore decision (user-wins, unknown-id drop, expired-timer→off).
2. `playback/TimerLogic.kt` + `TimerState.kt` + `playback/TimerLogicTest.kt`
   — three modes anchored to `elapsedRealtime()`, normalizeRestore validity window,
   10×1 s fade schedule, `formatRemaining`-friendly remainingMs contract.
3. `playback/QueueStore.kt` (`QueueStore` + `InMemoryQueueStore`) +
   `playback/QueueStoreTest.kt` — ids-only queue, append+de-dup, clamped
   moveUp/moveDown, filter remove, clear, peekNext/takeNext, setInitial.
4. `playback/PlaybackService.kt` + `PlaybackRepository.kt` — timer mode
   set/clear/minutes/end_of_story API (`currentTimer`, `timerRemainingMs()`,
   `setTimerMinutes/setTimerEndOfStory/clearTimer`, `addTimerListener` with
   `TimerSnapshot(state, remainingMs)`), 1 Hz elapsedRealtime ticker,
   expiry → service fade → pause + off + immediate flush (queue untouched),
   end-of-story suppresses auto-advance; queue mutations (`queueSnapshot`,
   `enqueue`, `moveQueueUp/Down`, `removeFromQueue`, `clearQueue`,
   `addQueueListener`) and `flushNow()`/hydration gate.
5. Surviving layouts/drawables (fragment_queue.xml, item_queue_row.xml,
   fragment_timer.xml, bg_queue_cover.xml, bg_row_surface.xml,
   bg_timer_row_selected.xml, ic_chevron_up/down, ic_close_peach, ic_check_mint)
   and `strings.xml` Spanish registry (placeholder strings already removed; not re-added).
6. Layout/dimen/colore plumbing: `queue_*`/`timer_*`/`header_*` dimen,
   colors temporizador/fondo/texto/superficie/textoSuave, min_touch 52 dp.
7. Player layouts already declared `player_timer_line`(GONE by default)
   and CoverHaloView already exposed `setTimerActive` (wired now, below).

### What I finished (this recovery pass)

1. `ui/queue/QueueAdapter.kt` (new): ids-only adapter over the repository's
   queue store snapshot, resolving story data from the compiled-in catalog;
   56 dp cover via the shared thumbnail pipeline (ThumbCache), title = bold 2-line
   Button label; chevron-up / chevron-down / ✕(peach) in 52 dp touch targets
   (48 dp visuals); stable ids; `submit()` called ONLY on queue-mutation
   events (never on the 500 ms progress cadence).
2. `ui/queue/QueueFragment.kt` (rewritten from stub): binds fragment_queue.xml,
   ✕ → popBackStack; "Vaciar" peach/enabled when non-empty, soft/disabled
   when empty; RecyclerView + LinearLayoutManager; empty-state copy
   `@string/queue_empty` (spec-verbatim "La cola está vacía. Añade cuentos
    desde el menú ⋮ de una tarjeta o con el botón + del reproductor."—the
   brief's shorter string was superseded by the spec's suggested copy already in
   the registry; queue listener attached in onStart, removed in onStop +
   onDestroyView (repo.connect() idempotent.
)
3. `ui/timer/TimerFragment.kt` (rewritten from stub: five static 56 dp rows
   (15/30/45, "Al terminar este cuento", "Desactivar"), selected row = solid
   peach bg_timer_row_selected + dark bold text, others bg_row_surface + light
   text; tap applies via repo (`setTimerMinutes` / `setTimerEndOfStory` /
   `clearTimer`) and pops back immediately (spec "Choosing applies immediately
   and pops back"); current mode highlighted on open (incl. onStart re-entry.

4. `ui/player/PlayerFragment.kt`: wired the sleep-timer visibility (S5.4::
   TimerListener (1 Hz repository ticks) drives `player_timer_line`
   ("Temporizador: m:ss" via TimeFormat.formatRemaining, or the end-of-story
   string; GONE when Off) and CoverHaloView.setTimerActive(active == Minutes/
   EndOfStory); listener added in onStart (with an immediate snapshot bind
   using currentTimer + timerRemainingMs()), removed in onStop + onDestroyView.
−
5. `docs/delivery.md` (new: build flow (`./gradlew assembleDebug`), adb
   install −r + `--no-local` streamed fallback, ONE-TIME `adb uninstall
   com.julicuentos.app` (RN signature mismatch) before first install; versionCode
   monotonic policy (4 → 5 this slice); free-space note (~1.25 GB APK →
   ~2.5 GB free for adb staging); content pipeline note (assets user-owned,
   gitignored; tools/port-catalog.py regenerates stories.json + covers if
   desired); device acceptance checklist verbatim from specs/delivery(a)–(e +
   supporting) as the pending on-device run list (DV1–DV11, DV2 host-verified.

6. `app/build.gradle.kts`: versionCode 4 → 5.
7. tasks.md: S5.1–S5.9 marked [x]; S5.10 stays [ ] with
   (deferred-device); DV1, DV3–DV11 annotated (deferred-device)；
   S2.2/S2.3 deferred notes closed → [x] pointing at the slice-5
   materializations (QueueStore/TimerLogic + tests, both green）。

### Build evidence

- Gate 1: `./gradlew assembleDebug` → **BUILD SUCCESSFUL**
  (see numbered evidence below — full-output line this slice's run)。
- Gate  ️2: `./gradlew testDebugUnitTest` → **BUILD SUCCESSFUL**;
  persist tests (PersistedStateParserTest, RestoreCoordinatorTest),
  playback tests (QueueStoreTest, TimerLogicTest), + TimeFormatTest all green.

### Deviations and risks

1. Queue-empty copy: kept the existing string (spec "Suggested copy" verbatim,
   "…Añade cuentos desde el menú ⋮ de una tarjeta o con el botón +…") instead
   of the brief's shorter paraphrase — the spec + registry own the copy.
2. S2.2/S2.3 materialized under slice-5 names (`QueueStore`/`TimerLogic`)
   rather than the slice-2 file names (`QueueLogic.kt`/`SleepTimer.kt`+`Fade.kt`);
   closed as [x] with the alias noted, so task accounting matches the real tree.

3. `stub_padding`/`stub_gap` dimen + TextAppearance.Jc.16/18 remain unused
   by the queue/timer screens (their layouts use the player/catalog styles) —
   dead-but-harmless resources, untouched.
4. On-device acceptance remains the single open gate (DV1, DV3–DV11,
   S5.10) — host evidence covers build/tests/static manifest audits only.

## Design polish pass (Opus consult implementation)

Implements openspec/changes/migrate-to-native-android/design-consult-opus.md (C1–C12)
on top of the slice-5 tree. No commit made (per brief). versionCode 5 -> 6.

### What changed per change id

- C0 tokens: +3 colors (`superficieAlta #332E5C`, `matte #201C3D`, `borde #3D3860`).
- C1 landscape player: symmetric 560+32+560 dp split (panel 560 dp, pad 28 land / 24
  port, corners 24); right column wrapped in `bg_panel` (superficie + 1dp borde
  hairline); strict 8/16/24 ladder (title 0 / desc 8 / seek 24 / transport 24 /
  chips 16 / timer 16); hero title `TextAppearance.Jc.28` (new rung); close button
  56 dp `bg_circle_surface` + 28 dp glyph at 16 dp margin.
- C2 cover: `CoverHaloView` rect-aware (width/height independent clip rects, fixed
  dp(20) corners, outline `setRoundRect`), `FIT_CENTER` + matte background; land
  cover 560x315 via `player_cover_w/_h`, portrait cover match_parent x 396 dp.
- C3 transport: primary row -15/play/+15 (60/88/60 dp, gap 20, icons 32/40);
  labelled chip row Temporizador / Añadir / Ver cola (bg_chip_control, min 52 dp,
  icon 24 + 8 gap + Nunito Bold 14); timer chip swaps to bg_chip_timer_on + dark
  label/icon when armed (PlayerFragment.setTimerChipArmed); new string `anadir`;
  new glyph `ic_list` for "Ver cola".
- C4 seek: `bg_seek_thumb` 20 dp; labels moved BELOW the 6 dp bar (gap 6); removed
  the seek-block double inset; kept the in-flight `<clip>` progress fix.
- C5 catalog card: fixed `H,16:9` cover on `bg_cover_matte` with `clipToOutline`
  (StoryHolder.init), title fixed `lines=2` (Fredoka 16, margins 4), synopsis
  Nunito 13 sp `lines=2` bottom-constrained (dead band gone), overflow moved onto
  the cover top-right (52 dp `bg_overflow_circle`, 20 dp glyph), pill/chip margins
  6 dp, `corner_pill` 8 dp.
- C6 grid: w1024dp -> 4 columns; gutters 8/8/40 (bottom 12 with miniplayer);
  header pad 24/16, subtitle 14 sp marginTop 4.
- C7 portrait player: cover OUTSIDE the panel (match_parent x 396 dp, 24 margins),
  24 dp gap, panel match_parent + 24 margins + bg_panel + 24 padding; same ladder.
- C8 miniplayer: floating card (margins 8, corners 20 all + borde stroke,
  clipToOutline), bar 72 dp, strip 4 dp, cover 56 matte fitCenter + clipToOutline,
  pad 16, title Fredoka 16, play chip corners 16, icons 26 (play/next).
- C9 queue rows: gaps 8, min height 76, cover 60 matte fitCenter + clipToOutline,
  corners 20 (via corner_card), title Fredoka 16 + duration line Nunito 12 sp
  (#AAA3CE, TimeFormat.formatTime), row buttons 26 dp glyphs (chevrons re-tinted
  soft via ImageView tint, remove stays peach).
- C10 timer rows: 64 dp / gap 10, left-aligned with 20 dp start padding, selected
  row keeps solid peach (corners 20 via corner_card) + dark bold + trailing
  pre-tinted `ic_check_dark` (24 dp) via compound drawable; helper paddingBottom 16;
  list insets 20.
- C11 "Marco Sonando": `bg_card_sonando` (4 dp mint frame) swapped in
  StoryAdapter.bindPill; CoverHaloView peach timer ring 3 -> 4 dp.
- C12 queue order: 32 dp `bg_index_circle` (Fredoka 18, #17152E) bound as
  position+1; current row swaps to peach `bg_index_circle_current` (adapter
  setCurrentId fed by QueueFragment).

### Files touched (27)

Res: colors, dimens (values, values-land, values-h720dp-land), styles, strings,
integers (values-w1024dp), 11 drawables added (bg_panel, bg_circle_surface,
bg_chip_control, bg_chip_timer_on, bg_cover_matte, bg_overflow_circle,
bg_card_sonando, bg_index_circle, bg_index_circle_current, ic_list, ic_check_dark)
+ 3 rewritten (bg_miniplayer, bg_seek_thumb, bg_seekbar_track kept as-is).
Layouts: fragment_player (portrait), layout-w600dp-land/fragment_player,
item_story_card, fragment_catalog, view_mini_player, item_queue_row,
fragment_timer. Kotlin: CoverHaloView, PlayerFragment, StoryAdapter,
MiniPlayerView, QueueAdapter, QueueFragment, TimerFragment.

### Build evidence

- Gate 1: `./gradlew assembleDebug` -> BUILD SUCCESSFUL (per group A/B/C + final).
- Gate 2: `./gradlew testDebugUnitTest` -> BUILD SUCCESSFUL, 61/61 tests green
  (QueueStore 19 / TimeFormat 5 / RestoreCoordinator 7 / TimerLogic 12 /
  PersistedStateParser 18).

### Deviations from the consult

1. `player_panel_w/_h` + `player_cover_w/_h` also defined in the default
   `values/dimens.xml` (same 560/315 values): AAPT2 refused to link qualifier-only
   dimens referenced from `layout-w600dp-land`; the portrait layouts never read
   them, so this is inert.
2. `queue_btn` stays 48 dp for the header ✕ glyphs; the row chevrons/remove use a
   new `queue_row_icon` 26 dp (consult's "48 -> 26" applies to row buttons only).
3. Portrait cover uses the consult's sanctioned alternative ("match_parent with
   396 dp height") instead of a values-port 704x396.
4. `bg_index_circle_current` is a second drawable (not in the +8 token list) to
   express the peach current-item variant cleanly on API 22.
5. The brief's note about `values-h720dp-land` holding a stale `player_cover 460dp`
   did not match the tree (that bucket only had section_gap/play_size/bottom_pad);
   the base `player_cover 340dp` / `player_controls_width 520dp` were removed.
6. `player_bottom_padding`/`player_section_gap` remain defined but unused by the
   new player layouts (dead-but-harmless, same precedent as stub_*; section_gap
   still drives the empty/error overlays).
