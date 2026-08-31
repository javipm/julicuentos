# Exploration — migrate-to-native-android

**Phase:** explore · **Change:** migrate-to-native-android · **Date:** 2026-08-30
**Source explored (read-only):** `~/julicuentos-rn` (Expo SDK 49 / RN 0.72 / expo-av)
**Target project:** `~/julicuentos-native` (Kotlin + XML views)
**Device:** Amazon Fire HD 10 7ª gen (KFSUWI / `suez`), Fire OS 5.3.x, Android 5.1.1 **API 22**, 1920×1200 @ ~1.5 density (≈1280×800 dp), no Google Play Services, sideload via adb (serial `<adb-serial>`).

Nothing in the source repo was modified, and no generator/build was executed.

---

## 1. Data model

### 1.1 Generated catalog — `.generated/catalog.ts`

Emitted by `scripts/generate-catalog.mjs` (runs as every `pre*` npm hook; the file is **gitignored**, so it must never be a source of truth for the native port — `content/` is).

```ts
export const CATALOG: Story[] = [ /* … */ ];
export const CATALOG_EMPTY = false as const;
export function getStoryById(id: string): Story | undefined;
```

Ordering is `fs.readdirSync(content).filter(dirs).sort()` → **alphabetical by folder id**. Verified first/last: `101-dalmatas, aladdin, alicia-…, bambi, blancanieves-…, brave, buscando-a-nemo, cars-2, dumbo, el-libro-de-la-selva, el-rey-leon, enredados, la-cenicienta, la-sirenita, los-increibles, monstruos-s-a, peter-pan, pocahontas, rompe-ralph, toy-story`. `playNext()`'s circular "next in catalog" uses **this** order, not the TSV order.

### 1.2 `Story` shape (`src/types/story.ts`)

| Field | Type | Notes |
|---|---|---|
| `id` | string | must equal folder name (generator hard-fails otherwise) |
| `titulo` | string | required, non-empty |
| `descripcion` | string | may be `""`; UI falls back to `"Cuento infantil"` (player) / `"Cuento"` (card) |
| `duracionSegundos` | number | `Math.round(Number(tsv))`, ≥ 0; used as duration fallback |
| `fechaPublicacion` | string | free text / RFC-822; **never displayed anywhere in the UI** |
| `cover` | ImageSource | `require(content/<id>/cover.jpg)` — player + miniplayer |
| `thumbnail` | ImageSource | `require(content/<id>/thumbnail.jpg)` — grid only |
| `audio` | union | `{mode:"local", source: number, file}` or `{mode:"remote", uri}` |

Also declared: `PlaybackStatus = idle|loading|ready|playing|paused|error` (type-only; the provider actually exposes `isPlaying` + `isLoading` + `error`, i.e. a 4-flag approximation, not a real state machine — the native port should implement the declared enum properly).

### 1.3 `content/<id>/info.tsv` and `content/cuentos.tsv`

Both share the same 8 columns (tab-separated, header row + **exactly one data row** is parsed for `info.tsv`; the generator fails if any required column is missing):

`id · titulo · descripcion · duracion_segundos · audio_mode · audio_file · audio_url · fecha_publicacion`

`cuentos.tsv` is the master export of the Python downloader (`scripts/descarga_audiocuentos.py`) and is **not read at runtime** by the app — only the per-story `info.tsv` files are.

Generator validation rules worth mirroring in a native content build step:

- `local`: `audio_file` defaults to `audio.mp3`, file must exist, `audio_url` must be empty.
- `remote`: `audio_url` must parse as **HTTPS**, `audio_file` must be empty; a stray `audio.mp3` is a warning, not an error.
- `thumbnail.jpg` auto-created from `cover.jpg` (Pillow 512 px resize, plain copy as fallback).
- Cover policy: original JPEG preserved if square and side ≤ 2048 (feed ships 1400×1400); center-crop otherwise; re-export quality 95, subsampling 0.

**All 20 stories are `audio_mode=remote`** today: every `audio_url` is an `https://www.ivoox.com/…mp3`. Total ≈ 650 MB streaming-only, with **no disk cache** (documented explicitly in `README.md`).

### 1.4 Native asset layout already present

`app/src/main/assets/audio/<id>.mp3` exists in the target repo. Sampled `aladdin.mp3`: MPEG with **ID3v2.3** carrying `TALB="Colección de audiocuentos Disney"`, `TPE1/TPE2="Disney"`, `TIT2="Aladdin"`, `TRCK="04"`, `TPOS=1`, `TDRC=1992`, `TCON="Infantil"`, `TXXX:comment=<Spanish synopsis>`, and an **embedded APIC PNG cover (~580×580)**. Consequences: the native app can source title/artwork from ID3, but should still ship an explicit catalog (JSON asset or a Kotlin constant list) for stable ids, durations, grid thumbnails and the description used by the player.

---

## 2. Screen-by-screen behavior

Router: expo-router `Stack`, `headerShown: false`, `contentStyle.backgroundColor = #17152E`, animation `none` on API ≤ 22 (`src/lib/stackAnimation.ts`). **No screen takes navigation params** — all state is shared through the root `PlayerProvider`. That maps directly to: one Activity + Fragments (or one Activity + multiple Activities) sharing a single `PlayerViewModel`/`PlayerRepository` scoped to the process.

### 2.1 `app/index.tsx` — Catalog

- `SafeAreaView` (top/left/right), flat `#17152E` background, explicit `flex: 1` (comment: NativeWind `className` alone fails on API 22 and pushes the miniplayer off-screen).
- Header block (`px-5 pt-4 pb-2`): title **"Julicuentos"** (Fredoka, `text-3xl`, `#F8F7FF`) + subtitle **"Elige un cuento y empieza a escuchar"** (Nunito Sans 400, `text-base`, `#AAA3CE`).
- `FlatList` grid, `numColumns` from logical width (`catalogListPerf.mjs`): `≥1024 → 5`, `≥768 → 3`, else `2`. Fire HD 10 landscape (1280 dp logical) → **5 columns**; portrait (800 dp) → **3 columns**.
  - Perf tuning: `windowSize 5`, `maxToRenderPerBatch cols*2`, `initialNumToRender cols*3`, `updateCellsBatchingPeriod 50`, `removeClippedSubviews` on Android only. `extraData = "<currentId>:<0|1>"` — deliberately excludes position so cells don't rebind on ticks.
  - Content padding: horizontal 12, bottom 16 when the miniplayer is visible, else 32.
- Empty-catalog state: "Añade carpetas en content/<id>/ con cover.jpg e info.tsv…" + inline `npm run catalog`. **Drop this screen state in the native port** (catalog is compiled in).
- **Card** (`StoryCard`): `flex 1/cols`, `p-2`, surface `#28244B`, `rounded-2xl` (16 dp), square thumbnail (`aspectRatio 1`, `contentFit cover`) with a mint **"Sonando"** pill (`bottom-2 left-2`, `#72E0B8` bg, `#17152E` bold text, no elevation on API ≤ 22) and a text block (`p-3`): title Fredoka `text-base` 2 lines, description Nunito `text-sm` 2 lines `#AAA3CE`. Top-right 44×44 (min 52) `⋮` button on `rgba(23,21,46,0.55)` opens a bottom-sheet `Modal`.
  - Sheet: story title (Fredoka `text-lg`), **"Reproducir ahora"** (solid mint), **"Añadir a la cola"** (mint outline/border), **"Cancelar"** (plain soft text). All rows `minHeight 52`, `rounded-2xl`.
- **Row tap semantics** (`resolveOpenStoryAction` in `openStoryLogic.ts`):
  - tapped == current **and playing** → just `navigate` to player (no interruption).
  - tapped == current **and paused** → `resume_and_navigate` (toggle play, then navigate).
  - different story → `playStoryNow(id)` + navigate immediately, **without awaiting the audio load** (deliberate: API 22 + remote latency).
- **MiniPlayer** (only on catalog; **in-flow footer, not an overlay** — absolute overlay went off-screen on API 22):
  - Bar height 64 dp + 6 dp progress strip + `max(insets.bottom, 8)`; bg `#28244B`, top corners 12, `zIndex 10`.
  - Contents: 48×48 rounded-8 cover, title Fredoka `text-base` 1 line, status line Nunito `text-xs` **"Sonando"** / **"En pausa"**, then play/pause as a **52 dp mint rounded-square** chip (`CONTROL_CORNER_RATIO 0.22` → radius ≈ 12; never a circle: circles pixelate on Fire HD even without elevation), then a flat `play-skip-forward` icon (52 dp hit).
  - Progress strip is **non-interactive** (visual only), solid colors (`#3D3860` track, `#72E0B8` fill, ≥ 5 px because a 3 px semi-transparent top strip was invisible on API 22).
  - `onNext` = `playNext()`: queue head first, else circular next in catalog.
  - Hidden entirely when `current == null`.
  - Helper `miniPlayerListPadding(bottomInset) = 64 + 6 + max(inset,8) + 16` is exported but the screen hard-codes 16 instead, since the bar is in-flow.

### 2.2 `app/player.tsx` — Full player

- Top bar: `✕` back (min 52), centered **"REPRODUCIENDO"** label (Nunito `text-sm`, `#AAA3CE`), spacer of `minTouch` width.
- Body is a `ScrollView` with `flexGrow: 1`, `justifyContent: center`, two modes resolved by `resolvePlayerLayout(width, height)`:
  - **stack** (portrait): cover halo → title → description → seek → transport → "Ver cola".
  - **split** (landscape with `w ≥ 600 && w/h ≥ 1.15`, i.e. Fire landscape 1280×752): row = `[cover] [ title/seek/transport/queue column, max 520 dp ]`, gap 28, `maxWidth 1100`, no scrolling.
  - `compact` (landscape or `h < 720`) tightens spacing: `sectionGap 12` (else 24), `playSize 64` (else 72), `bottomPadding 16` (else 32), `titleMarginTop 12` (else 24), description **1 line** (else 2). Cover: `min 120`, `max 340`, `0.72 * width` (split: `0.42 * width`, minus halo, leaving ≥ 320 dp for controls).
- `CoverHalo` (progressive rewrite): three stacked **rounded-square frames** (`COVER_CORNER_RATIO = 0.12`) instead of a real arc — base mint ring at 28 % alpha, progress ring whose four borders light up at thresholds `> 0.02` top, `> 0.28` right, `> 0.55` bottom, `> 0.8` left, and a peach 3 dp ring when the sleep timer is active. Cover image `fadeDuration = 0` on API ≤ 22 (200 ms fade was expensive). No animation, no reanimated, no SVG.
- Title Fredoka `text-2xl` (2 lines), description Nunito `text-base` `#AAA3CE`, timer line Nunito `text-sm` `#FFB66E`: `"Temporizador: al terminar este cuento"` or `"Temporizador: m:ss"` (countdown, 1 Hz).
- Loading line: `ActivityIndicator` mint + "Cargando audio…".
- Error card (`#28244B`, radius 16): message + **"Reintentar"** (only when mode is remote) and **"Cerrar"**; both `minHeight 52`.
- Transport row `[timer] [-15] [play] [+15] [+queue]`, all siblings (no nested pressables), gap 8, side buttons min 52×52, play = mint circle `72`/`64` with dark icon 34.
  - `⏱` → `/timer`; `⏪` → `skipBy(-15_000)`; `⏩` → `skipBy(+15_000)`; `▶︎/⏸` → `togglePlayPause()`; `＋` → `addToQueue(current.id)`, **disabled and swapped for a mint ✓ when the current story is already in the queue**.
- "Ver cola" pill (`#28244B`, radius 16, min 52) → `/queue`.
- Empty state (`current == null`): centered "No hay ningún cuento en reproducción." + mint "Volver al catálogo" (min 52).
- **No previous-track button, no shuffle, no repeat, no speed control, no chapter list.**
- State isolation: only `PlayerSeekBlock` and `PlayerCoverHalo` subscribe to `positionMs`; the shell subscribes to state + actions only.

### 2.3 `app/queue.tsx` — Queue

- Header: `✕`, **"Cola"** (Fredoka `text-xl`), **"Vaciar"** text button (peach when non-empty, soft-purple when empty/disabled).
- Empty: "La cola está vacía. Desde el catálogo, usa el menú de una tarjeta para añadir cuentos." (note: the catalog's **card body tap is play-now**, so this copy is slightly misleading — the real add affordances are the `⋮` sheet and the player's `＋`).
- Row: `#28244B`, radius 16, `minHeight 72`, `p-3`, `mb-3`: 56×56 rounded-10 cover, title bold Nunito 2 lines, then three 48×48 buttons: **chevron-up**, **chevron-down**, **✕** (peach).
- **Queue semantics** (`queueLogic.mjs`, all pure functions on `string[]` of ids):
  - `enqueue` — append, **de-duplicated** (`includes(id)` returns a copy unchanged).
  - `removeFromQueue(id)` filters by id; `clearQueue()` → `[]`; `moveUp/moveDown` are index swaps with clamped no-ops (no wraparound).
  - `playNowClearsQueue()` → `[]`: **"Reproducir ahora" wipes the queue**. This is the most surprising rule and must be a conscious decision in the native port.
  - `takeNext(queue)` pops the head.
  - `resolveNext(queue, catalogIds, currentId)`: queue head if any; otherwise the **circular next id in the alphabetical catalog** (`(idx + 1) % length`).
  - **Auto-advance ≠ manual next**: `onEnded()` uses `takeNext`, so when the queue is empty the app **stops** (`isPlaying = false`) and does **not** wrap the catalog; the `⏭` button uses `resolveNext` and **does** wrap. Preserve or fix deliberately.
  - Only ids are persisted; the queue is derived by `getStoryById`, so unknown ids silently vanish on restore (self-healing on catalog edits).

### 2.4 `app/timer.tsx` — Sleep timer

- Header `✕` + "Temporizador" (Fredoka `text-xl`); helper copy: **"Al vencer, se pausa la reproducción y se conserva la cola."**
- Options (single-select, 56 dp rows, radius 16): **15 minutos**, **30 minutos**, **45 minutos**, **Al terminar este cuento**, **Desactivar**. Selected = solid `#FFB66E` with `#17152E` bold text; unselected = `#28244B` with `#F8F7FF`. Choosing any option immediately `router.back()`.
- `TimerMode = {kind:"off"} | {kind:"minutes", minutes:15|30|45, endsAt:number} | {kind:"end_of_story"}`.
  - `setMinutesTimer(m)` → `endsAt = Date.now() + m*60_000` (**wall clock, persisted, not playback-relative**; 45 min of timer on a 35 min story can therefore advance into the next story… except auto-advance is blocked only when the timer is `end_of_story`).
  - 1 Hz `setInterval` in the provider calls `onTick()`; on expiry → `setTimer({kind:"off"})`, `sound.pauseAsync()`, `isPlaying = false`. **Queue is untouched.**
  - `end_of_story` is handled in `onEnded()`: pause, `timer → off`, **no auto-advance**.
  - `timerLogic.shouldPauseAtEndOfStory()` (true for both `end_of_story` and `minutes`) exists and is unit-tested but **is never called by the provider** — dead-but-intended logic; do not port it blindly.
- **No screen-off protection**: the countdown runs on a JS 1 Hz interval, so it drifts/stops when RN is backgrounded. A native `AlarmManager`/handler-based timer fixes this.

### 2.5 `app/_layout.tsx` — Root

Gates on `useFonts([Fredoka_600SemiBold, NunitoSans_400Regular, NunitoSans_700Bold])`, keeps the native `#17152E` splash until fonts resolve (`preventAutoHideAsync` / `hideAsync`), renders `GestureHandlerRootView > SafeAreaProvider > PlayerProvider > Stack`, `StatusBar style="light"`, `enableScreens(true)`.

---

## 3. Playback core (`src/player/PlayerProvider.tsx` + `progressStore.ts`)

**Engine:** `expo-av` `Audio.Sound` (single instance in a ref), mode set before every load:

```ts
Audio.setAudioModeAsync({ allowsRecordingIOS:false, playsInSilentModeIOS:true,
  staysActiveInBackground:true, shouldDuckAndroid:true, playThroughEarpieceAndroid:false });
```

**Tick cadence:** `progressUpdateIntervalMs(os, version)` → **500 ms on Android ≤ API 22**, 250 ms otherwise, passed as `progressUpdateIntervalMillis`.

**Progress plumbing:** position/duration are **not** React state. They live in `createProgressStore()` — a `{positionMs, durationMs}` snapshot with a `Set<listener>`, no-op `set` when both values are unchanged — consumed via `useSyncExternalStore`. Three context splits (`usePlayerState`, `usePlayerActions` (stable identity), `usePlayerProgress`) plus `DurationFallbackContext` (catalog duration in ms, used when the audio reports `durationMillis ≤ 0`). The whole architecture exists to stop 4–8 Hz re-renders from killing the grid on old hardware — **the native port has no equivalent problem** (bind views, update a `SeekBar` + two `TextView`s via a `Handler`).

**Load path (`loadStory(story, startPositionMs, autoplay, {deferCreate, isRecovery})`):**

1. `gen = ++loadGenRef.current` — every status callback checks `gen`, silently discarding stale events; if `createAsync` resolves after a newer load started, the sound is immediately unloaded.
2. Reset recovery counters (unless `isRecovery`), set `wantsToPlayRef/isBufferingRef/failedSoundRef`, `isLoading = true`, `isPlaying = false`, `error = null`.
3. `await releasePlayer()` — nulls the listener, `stopAsync()`, `unloadAsync()`, `releaseBackgroundAudioLock()`. Prior audio is killed **before** anything new starts.
4. `createAndAttach()` — set audio mode, acquire background lock (only for remote + autoplay), `Audio.Sound.createAsync(source, {shouldPlay: autoplay, positionMillis, progressUpdateIntervalMillis}, onStatus)`.
5. With `deferCreate: true` (catalog → player path) steps 4 is postponed via `InteractionManager.runAfterInteractions`, so the player screen and cover paint before the blocking remote create.

That ordering is a **contract** pinned by `src/lib/openPlayerLoadOrder.ts` / `.mjs`: `select_state → release_previous → navigate → after_interactions → create_async`, validated by `isValidOpenPlayerLoadOrder()` (everything must precede `create_async`) and by unit tests. Native translation: state selection and screen transition first, player preparation async afterwards — one listener, no double-audio window.

**Status callback behavior:** `!isLoaded && error` → clear loading/playing, and if remote → `recoverRemote(story, position, skip=true)`, else `error = "Error al cargar el cuento."`; `!isLoaded && !error` → loading. On loaded: `isBuffering`, `shouldPlay` → `wantsToPlay`, `durationMillis` with catalog fallback, progress → store, `isPlaying` only when it actually changed, `didJustFinish` → `onEnded()`.

**`togglePlayPause()`** — no sound or `failedSoundRef` → reset attempts and `recoverRemote(..., skip=false)`; pause → release bg lock + `pauseAsync()`; play → `wantsToPlay = true`, acquire lock (remote) + `playAsync()`; throws → recovery for remote, `"No se pudo cambiar la reproducción."` otherwise.

**`seekTo(ms)`** — clamps to ≥ 0. If no sound / failed / **stream stuck** (`remote && isBuffering && now - lastAdvancedAt ≥ 12 s`) it updates the progress store optimistically and **reloads the whole sound at the target position** (`isRecovery`). Otherwise `setPositionAsync(ms)` then updates the store; on throw with remote mode, reload at target.

**`skipBy(delta)`** — reads position from a ref, clamps to `[0, duration]`, delegates to `seekTo`.

**`playStoryNow(id)`** — `markUserMutated()`, `queue → []`, `current → id`, `setProgress(0, catalogDuration)`, then `loadStory(story, 0, autoplay=true, {deferCreate:true})`. Navigation happens in parallel from the screen.

**`playNext()`** — `resolveNext(queueIds, CATALOG.map(id), currentId)` (queue, else circular catalog), replaces current, **autoplay true**, position 0.

**`onEnded()`** — `wantsToPlay = false`, release lock; `end_of_story` timer → pause + `timer off` + stop (no advance); otherwise `takeNext(queue)`: if a next story exists, `loadStory(next, 0, autoplay=true)`; if not, `isPlaying = false` and the (empty) queue stays.

**`retryRemote()`** — resets recovery counters, reloads at the current position with autoplay.

**Autoplay summary:** every explicit user action autoplays (play-now, next, auto-advance, retry, recovery). **The only non-autoplay load is cold-start restore.**

---

## 4. Persistence (`src/lib/persistenceLogic.ts` → `persistenceLogic.mjs`)

**Exactly one AsyncStorage key:** `julicuentos.player.v1` (constant `STORAGE_KEY`), value = `JSON.stringify(PersistedPlayerState)`. No other keys, no positions per story, no history, no favorites, no volume.

```ts
type PersistedPlayerState = {
  currentStoryId: string | null;
  positionMs: number;
  queueIds: string[];
  timer: TimerMode;          // {kind:"off"} | {kind:"minutes",minutes:15|30|45,endsAt} | {kind:"end_of_story"}
  updatedAt: number;         // Date.now(), written but never read (no TTL)
};
```

- Written every **`PERSIST_INTERVAL_MS = 5000`** and on any `AppState !== "active"` and on provider unmount.
- **Guarded writes:** `persist()` returns early until `hydratedRef.current` is true — an explicit fix so a StrictMode/remount cleanup cannot blank the stored queue.
- **Guarded restore:** if `userMutatedRef.current` is true (the user already pressed something while AsyncStorage was in flight), the snapshot is discarded rather than clobbering live state.
- `parseState` is fully defensive (bad JSON → `null`; negative/NaN position → 0; non-string ids filtered out; `normalizeTimer` rejects any minutes ∉ {15,30,45} or missing `endsAt` → `{kind:"off"}`). Keep this validator-first shape in Kotlin (`kotlinx.serialization` + safe defaults).
- Restore also seeds `setProgress(positionMs, storyDuration)` and loads the story with **autoplay false**.
- `createDefaultState()` / `restoreForIdle(state)` (identity) are legacy/no-ops — do not port.

Native equivalent: `SharedPreferences` (or DataStore-preferences) single JSON string, 5 s `Handler` flush + `onStop` flush. Position updates come for free from the player callback, so no extra timer is needed beyond the periodic flush.

---

## 5. Reliability hacks (the Expo-era bugs the native rewrite must actually fix)

### 5.1 `src/lib/backgroundAudio.ts` — **the smoking gun for pain point #1**

```ts
const nativeModule = NativeModules.JulicuentosBackgroundAudio as { acquire?(): void; release?(): void } | undefined;
export function acquireBackgroundAudioLock() { if (Platform.OS === "android") nativeModule?.acquire?.(); }
```

A repo-wide grep finds **only this file** referencing `JulicuentosBackgroundAudio`. There is **no `android/` folder, no Expo Module, no config plugin, no Java/Kotlin implementation** (`README.md` and `docs/android-build-readiness.md` confirm `android/`/`ios/` are intentionally absent, managed + EAS CNG). So `nativeModule` is `undefined` and **every acquire/release call is a silent no-op**. What actually keeps audio alive is only `expo-av`'s `staysActiveInBackground: true` plus declared `FOREGROUND_SERVICE`/`WAKE_LOCK` permissions in `app.json` — no foreground service, no notification, no `PARTIAL_WAKE_LOCK`, no owned `MediaSession`, no `MediaButton` receiver.

That explains the reported symptom precisely: playback survives backgrounding briefly, then dies **after the screen turns off** (CPU sleeps, and/or Fire OS 5.3's aggressive app caching stops the undecorated background process). `docs/android-build-readiness.md` even lists it as untested ("Requires prueba en dispositivo") and states lock-screen / media-session controls are **not** available with expo-av.

**Native must:** `MediaSessionService` + `Media3 ExoPlayer`, `android:foregroundServiceType="mediaPlayback"`, `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` + `WAKE_LOCK`, `MediaController.setWakeMode(WAKE_MODE_NETWORK)` (or the player's own wake mode) so the CPU stays awake with the screen off, a `MediaStyle` notification with play/pause/±15/next, and a `MediaButton` receiver. This single change deletes 100 % of `backgroundAudio.ts`.

### 5.2 `src/lib/streamRecovery.ts` / `.mjs` — patching remote streaming

Constants: `STREAM_STALL_TIMEOUT_MS = 12_000`, `STREAM_RECOVERY_SKIP_MS = 2_000`, `STREAM_RECOVERY_RESET_MS = 30_000`, `MAX_STREAM_RECOVERY_ATTEMPTS = 3`.
Helpers: `canRecoverRemoteStream({mode,attempts,inFlight})` (remote, not already in flight, < 3 attempts); `isRemoteStreamStalled({mode,wantsToPlay,isBuffering,lastAdvancedAt,now})` (remote + wants to play + buffering + 12 s without position advancing); `streamRecoveryPosition(pos, dur)` = `min(pos + 2000, dur - 1000)`.

Provider wiring: a watchdog `setInterval(…, 3000)` plus an `AppState → "active"` hook scan for stalls; recovery bumps `attempts`, waits **300 ms**, and **fully reloads the sound 2 s ahead**; the counter resets after 30 s of real progress; exhausted attempts surface `"La conexión de audio se ha interrumpido. Pulsa reintentar."`. The "skip +2 s" heuristic exists to hop over a corrupt/blocked segment on ivoox's CDN.

**All of this disappears with bundled audio.** Keep only: a load error → snackbar + retry, and `setWakeMode`/`Player.Listener.onPlayerError` → `prepare()` once. This is the biggest reliability win of the rewrite, and it also kills the CORS/web branch and the "error card" UI that exists solely for remote mode.

### 5.3 `src/lib/progressInterval.ts` (+ siblings) — patching API 22 rendering

`500 ms` ticks on Android ≤ 22 instead of 250 ms ("reduce re-renders a la mitad sin degradar controles"). Same family of hacks: `stackAnimation → "none"`, `coverImageTransition → 0 ms`, `catalogListPerf` (small batches, `removeClippedSubviews`), `progressStore` + three-way context split + `memo` on `StoryCard` with a custom prop comparator, `extraData` excluding position, `crispCircle`/`crispRoundedSquare` (**no elevation on Android at all**: "elevation on rounded Views is rasterized with jagged edges on API 22"), `MiniPlayer` progress ≥ 5 px with opaque colors, `zIndex`/footer-in-flow workaround, explicit `flex: 1` / `minHeight` because "NativeWind className solo falla en API 22" and "API 22 collapses ScrollView when only flexGrow is used".

Native equivalents are trivial but the **constraints carry over as performance budget**: flat solid fills, no elevation shadows on small circles, no blur, no animated vectors, few overdrawn full-screen layers, RecyclerView with recycled stable cells.

### 5.4 Other latent bugs found while reading

- `playStoryNow` clears the queue with no confirmation and no undo → a child can lose a built-up playlist with one tap.
- `restore` with an expired `endsAt` (minutes timer) pauses within one tick of the 1 s interval — reads as "the app killed itself on start".
- `onEnded` while `kind === "minutes"`: the timer keeps running across auto-advanced tracks (wall clock), so a story can be cut mid-way — arguably intended for a sleep timer, but worth confirming.
- `queue.tsx` empty-state copy points the user at a card menu, while the player's `＋` is the more discoverable affordance.
- `fechaPublicacion` is collected, validated and stored, and **never shown**.

---

## 6. Seek bar (`src/components/SeekBar.tsx`) — why scrubbing is broken

Implementation: a 44 dp-high `View` (4 dp track, `rgba(170,163,206,0.4)`, `#72E0B8` fill, absolute 16 dp `crispCircleStyle` thumb at `left: progress*100%`, `marginLeft: -8`), width captured once via `onLayout`, and **all interaction done with the React Native responder system**:

```
onStartShouldSetResponder → true      onResponderGrant  → seekFromLocationX
onMoveShouldSetResponder  → true      onResponderMove   → seekFromLocationX
onResponderTerminationRequest → false onResponderRelease→ seekFromLocationX
```

`seekFromLocationX(x) = onSeek(clamp(x / width, 0, 1) * durationMs)` → `seekTo(ms)` in the provider. Accessibility: `role="adjustable"` with min/max/now in seconds (TalkBack step-increment works; no custom actions).

Defects, ranked by how likely they cause the user's complaint:

1. **No local drag state — a real seek fires on every touch event.** There is no `isDragging`; the thumb renders `positionMs / durationMs` from the provider. Each `onResponderMove` calls `setPositionAsync`, and the next expo-av status callback (250–500 ms later on this device) reports a **stale or rounded position**, so the thumb snaps backwards under the finger, stuttering toward the tap point. This is the core bug and the classic reason "scrubbing is broken".
2. **`seekTo` can trigger a full reload mid-drag.** If the stream is stalling (`isBuffering` and no advance for 12 s) or the sound is missing, every single move event takes the `loadStory(..., isRecovery:true)` path: unload + `createAsync` at the dragged position — dozens of times per gesture, with a 300 ms recovery delay in the mix.
3. **Parent `ScrollView` steals the gesture.** The seek bar lives inside a vertically scrolling `ScrollView` (`bounces`). `onResponderTerminationRequest → false` cannot stop the native scroll from intercepting a diagonal drag, and there is **no `onResponderTerminate` handler**, so an interrupted drag simply stops updating with the thumb left wherever the last accepted event put it.
4. **Coordinate/geometry fragility.** `width` starts at `1` before the first layout (first tap on a freshly mounted screen can compute a wild ratio, only saved by `clamp`), and `locationX` inside split/centered layouts with `alignSelf`/`maxWidth` is measured relative to the responder View; the thumb is positioned with `%` on a different ancestor than the one that receives `locationX`, so any inner padding shifts the mapping by half the thumb.
5. **No live time preview, no hysteresis, no throttle.** Nothing shows the target timestamp while dragging; there's no 30–60 ms throttle; no commit-on-release; and remote seeks land in unbuffered territory, which is what makes them slow and noisy.
6. **`durationMs` may be catalog metadata, not the real stream length** (ivoox MP3 duration vs `duracion_segundos`), so a tap can map past/before the actual end and then clamp.

**Native design that fixes it for free:** an Android `SeekBar` (or a small custom view) inside a non-scrolling container, with:
- `onStartTrackingTouch` → set `isSeeking = true`, stop applying player position to the thumb, show a time bubble at the thumb (`formatTime(target)`);
- `onProgressChanged(fromUser)` → update only the label/thumb; **never** call the player;
- `onStopTrackingTouch` → **commit once** `player.seekTo(ms)`, then clear `isSeeking`;
- while `isSeeking`, incoming playback position updates update the text/timer only, never the thumb;
- local file → `seekTo` is effectively instant (no buffering), so no recovery path is needed.
Keep ±15 s as a secondary, always-correct control, and keep the "commit-on-release" model — the RN version's continuous-seek behavior is a bug, not a design intent.

---

## 7. Theme, typography, formatting

**Palette** — `src/theme/colors.ts` and `tailwind.config.js` are byte-identical in values:

| Token | Tailwind class | Hex | Usage |
|---|---|---|---|
| `fondo` | `bg-fondo` | `#17152E` | global background, text-on-accent, splash, status bar dark |
| `superficie` | `bg-superficie` | `#28244B` | cards, queue rows, miniplayer, error card |
| `accion` | — (inline) | `#72E0B8` | play button/chip, progress fill, "Sonando" badge, primary buttons, active icons |
| `temporizador` | — (inline) | `#FFB66E` | sleep-timer text, timer ring, "Vaciar", remove-from-queue |
| `texto` | — (inline) | `#F8F7FF` | titles, primary text |
| `textoSuave` | — (inline) | `#AAA3CE` | subtitles, descriptions, inactive text |

Additional literals found in code (not tokens, must be ported explicitly): `#3D3860` miniplayer progress track; `rgba(170,163,206,0.4)` seek track; `rgba(114,224,184,0.28)` cover base ring; `rgba(23,21,46,0.55)` card `⋮` button; `rgba(0,0,0,0.5)` modal scrim; `#000` shadow color.

**Fonts** — `@expo-google-fonts/fredoka` + `@expo-google-fonts/nunito-sans`, only three faces loaded:

| Tailwind family | Face | Where |
|---|---|---|
| `font-display` | Fredoka 600 SemiBold | app title, card titles, player title, "Cola", "Temporizador", modal title |
| `font-body` | Nunito Sans 400 Regular | descriptions, statuses, time labels, secondary buttons |
| `font-body-bold` | Nunito Sans 700 Bold | transport/primary button labels, queue titles, "Sonando" badge |

Sizes in use: `text-3xl` (30) screen title · `text-2xl` (24) player title · `text-xl` (20) modal/queue/timer headers · `text-lg` (18) modal title · `text-base` (16) body/subtitles/miniplayer title · `text-sm` (14) card description, labels · `text-xs` (12) seek times, miniplayer status, badge. Native: ship the two TTFs (Fredoka-SemiBold, NunitoSans-Regular/Bold) in `res/font` with `fontFamily` styles, and define `TextAppearance`s for these tokens.

**Geometry tokens** — `minTouch 52`, `playSize 72`/`64`, `controlGap 8`, `contentPaddingH 24`, card radius 16 (`rounded-2xl`), sheet radius 24 (`rounded-t-3xl`), queue thumb radius 10, miniplayer cover radius 8, `COVER_CORNER_RATIO 0.12`, `CONTROL_CORNER_RATIO 0.22`, miniplayer 64 + 6 + ≥8, header height 56.

**Shadows** — `softShadow()` returns `boxShadow` on web and `shadowColor/Opacity/Radius/Offset + elevation` elsewhere, but `circleShadowAllowed(os) => os !== "android"` means **no elevation is ever applied to circular/square controls on Android**, and the StoryCard "Sonando" badge drops its shadow entirely on `Platform.Version <= 22`. Net effect on the target device: **a completely flat, shadowless design** — depth comes from `#28244B` surfaces on `#17152E`.

**Time formatting** (`src/lib/format.ts`, `timerLogic.mjs`):
- `formatTime(ms)`: floor to seconds, clamp negatives/NaN; `h:mm:ss` when `h > 0`, else `m:ss`. Since stories run 21–52 min, the player mostly shows `m:ss` (never hours) while the catalog durations are ≤ 52 min too.
- `formatRemaining(ms)`: **`ceil`** to seconds → `m:ss` (counts down to 0:00 without showing 0:00 early).
- `clamp(n,min,max)`.

**Copy/voice** — Spanish, informal-child register, capitalized eyebrow label "REPRODUCIENDO", infinitive button labels ("Reproducir ahora", "Añadir a la cola", "Ver cola", "Vaciar", "Desactivar", "Reintentar", "Cerrar"), TalkBack labels on every control with hints. Port the strings verbatim into `strings.xml` for a like-for-like UI, then improve.

---

## 8. Story content (from `content/cuentos.tsv`)

All 20 entries: `audio_mode = remote`, `audio_file` empty, `audio_url = https://www.ivoox.com/...mp3`. TSV order is publication order; the runtime catalog order is alphabetical (§1.1).

| # | id | Título | dur (s) | mm:ss |
|---|---|---|---|---|
| 1 | `el-libro-de-la-selva` | El libro de la selva | 1604 | 26:44 |
| 2 | `rompe-ralph` | Rompe Ralph | 3090 | 51:30 |
| 3 | `pocahontas` | Pocahontas | 2160 | 36:00 |
| 4 | `monstruos-s-a` | Monstruos S.A. | 2980 | 49:40 |
| 5 | `enredados` | Enredados | 2295 | 38:15 |
| 6 | `brave` | Brave | 1996 | 33:16 |
| 7 | `alicia-en-el-pais-de-las-maravillas` | Alicia en el país de las maravillas | 1293 | 21:33 |
| 8 | `peter-pan` | Peter Pan | 2306 | 38:26 |
| 9 | `cars-2` | Cars 2 | 2411 | 40:11 |
| 10 | `toy-story` | Toy Story | 1497 | 24:57 |
| 11 | `los-increibles` | Los Increibles | 2843 | 47:23 |
| 12 | `la-sirenita` | La Sirenita | 1723 | 28:43 |
| 13 | `la-cenicienta` | La cenicienta | 2612 | 43:32 |
| 14 | `el-rey-leon` | El rey león | 2031 | 33:51 |
| 15 | `dumbo` | Dumbo | 1719 | 28:39 |
| 16 | `buscando-a-nemo` | Buscando a Nemo | 2255 | 37:35 |
| 17 | `bambi` | Bambi | 1923 | 32:03 |
| 18 | `aladdin` | Aladdin | 1903 | 31:43 |
| 19 | `101-dalmatas` | 101 Dálmatas | 1472 | 24:32 |
| 20 | `blancanieves-y-los-siete-enanitos` | Blancanieves y los siete enanitos | 1745 | 29:05 |

**Totals:** 20 stories · 41 858 s ≈ **11 h 37 m** · mean 2 093 s (34:53) · min 1 293 s · max 3 090 s. Longest single track ≈ 52 min — well inside a 15/30/45-min sleep timer, so the timer will frequently expire mid-story (design the "cut" to be gentle). Every story carries a full Spanish synopsis used on the card (2 lines) and in the player (1–2 lines). Data quirks to fix while porting: `bambi` description starts with a truncated word (`"ambi es un pequeño cervatillo…"`), `los-increibles` id/`titulo` lack the accent ("Los Increibles"), and `fecha_publicacion` is RFC-822 Spanish (`Mon, 17 Jun 2024 09:24:17 +0200`).

---

## 9. Constraints for the native target (API 22, Fire HD 10 2015)

**Runtime / API level**
- `minSdk 22` (or 21 to mirror the RN app). **API 22 has no notification channels** — every `NotificationChannel` call must be `Build.VERSION.SDK_INT >= O` guarded, yet a channel is still required for the Media3 notification on newer builds. Adaptive icons, `Notification.Action` semantics and `MediaStyle` differ pre-O; test the notification on the device, not just the emulator.
- **Media3 is safe on 22**: `androidx.media3:1.x` declares minSdk 21. `MediaSessionService` + `ExoPlayer` + `MediaController` with `SessionCommand` buttons (play/pause, ±15 s skip, next) works on Lollipop.
- **Kotlin + XML views (no Compose)** — confirmed decision; Compose is minSdk 21-capable but the runtime cost on 1.3 GHz Cortex-A53 + the RN-era evidence that alpha/blending is expensive make Views the right call.
- No `java.time`, no `Drawable#setTintList` on some legacy paths, no `Typeface` variable fonts, no `RenderEffect`/blur, no `VectorDrawable` animated tint — avoid all of it.
- Kotlin stdlib + AGP versions must still support `compileSdk 34`/`buildTools` while emitting API 22 bytecode; use core library desugaring **only** if a dependency needs it.

**No network at all**
- All 20 MP3s are bundled in `assets/audio/` (650 MB), so **no `INTERNET` permission** and no TLS/HTTP-stack concerns; the ivoox URLs become provenance metadata. Removing the network layer deletes the entire stream-recovery subsystem and the CORS/web branch.
- Keep MP3s uncompressed in the APK: `androidResources { noCompress "mp3" }` (MP3 is already entropy-coded; compressing wastes build time and slows first extraction). Asset paths keep folder ids with dashes (unlike `res/raw`, which requires `[a-z0-9_]` names), and `asset:///audio/<id>.mp3` resolves via Media3's `AssetDataSource`.
- Consider `AssetFileDescriptor` length limits for large single assets in some old kernels — verify ≥ 60 MB files play from `assets/` on the device early (this is the one technical unknown worth a spike).

**Size / install**
- APK ≈ 650–700 MB. Sideload with `adb install -r` stages the file in `/data/local/tmp` → **needs ~2× free storage**; use `adb --no-local install` (streamed) if the device is tight. No Play upload limits apply (personal app).
- Decide the artifact: a single APK (simplest sideload) vs split-ABI (irrelevant — audio dominates).

**Memory / CPU budget (2015 hardware)**
- ~2 GB RAM, ~1.5 GB usable per app, ART heap default (~192–256 MB, `largeHeap` available but avoid relying on it).
- `cover.jpg` is up to 1400×1400 → ~7.8 MB decoded per ARGB_8888 bitmap. With a 5-column landscape grid, decoding full-size covers would OOM instantly. The RN app already ships `thumbnail.jpg` (512 px) for the grid and `cover.jpg` only for the player/miniplayer — **the native port must preserve that split** and additionally downsample to target view size (`inSampleSize` / `BitmapFactory.Options` or `ContentResolver` + `ImageView` with `adjustViewBounds`), ideally `RGB_565` for large covers.
- Keep the bitmap cache small (e.g. `LruCache` ≈ 8–16 MB) or precompute: 20 thumbnails + 20 covers is tiny enough to bake downscaled variants into the APK instead of runtime decoding.
- UI cadence: 500 ms progress updates were chosen for API 22 in the RN build. Keep the seek bar / ring refresh at ~2–4 Hz (or drive the `SeekBar` from a `ValueAnimator`-free `Handler`), avoid `Invalidate` on full-screen layers, and avoid elevation/shadows/blur — the same reasons the RN code removed them ("elevation on rounded Views rasterizes with jagged edges", "fade alpha is costly", "overdraw").
- Prefer a shallow view hierarchy: `RecyclerView` grid with stable ids + `DiffUtil`, `ConstraintLayout` per screen, no nested scrolls, and **do not put the seek bar inside a scrolling container** (removes RN defect §6.3 at the platform level).

**Permissions / manifest**
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (API 34 target), `WAKE_LOCK`, `POST_NOTIFICATIONS` (API 33+ target, harmless on 22), `RECEIVE_BOOT_COMPLETED` only if we ever auto-resume. No microphone, no storage. `<queries>`/`PACKAGE_REPLACED` not needed.

**Delivery** — sideload only; keep `versionCode` monotonic so `adb install -r` upgrades work, and remember that the RN app's AsyncStorage lives in the old app's data dir and will **not** migrate automatically.

---

## Implications for native design

1. **The background-pause bug has a concrete, verified cause:** `NativeModules.JulicuentosBackgroundAudio` does not exist in the repo, so the wake/background lock was always a no-op. The native app must own a real `MediaSessionService` foreground service with `mediaPlayback` type and a wake lock, and must be verified by *screen off for 5+ minutes* on the device — not by trusting a permission entry.
2. **Bundling the MP3s deletes ~350 lines of defensive code**: `streamRecovery.*` (stall watchdog, 3 attempts, 300 ms backoff, +2 s skip), the remote-mode `Reintentar` card, CORS/web branches, and the "loading" path caused by network latency. Error UI collapses to "audio file missing/unreadable".
3. **SeekBar must be drag-preview + commit-on-release, not continuous-seek.** One `player.seekTo()` on `onStopTrackingTouch`, ignore incoming position updates while dragging, show the target timestamp while scrubbing. This is the fix for pain point #2, and it is a behavior change from the RN implementation, not just a rewrite.
4. **Single source of truth for playback state**: one `ExoPlayer` in a `ViewModel`/service-bound repository, with an explicit sealed state (`Idle/Loading/Ready/Playing/Paused/Ended/Error`) — the RN type declares that enum but never uses it, and the real UI state is three loosely-consistent booleans plus a nullable error.
5. **Progress must not drive view rebuilds.** Port the RN *intent* (external progress store, 500 ms on API 22, memoized grid) into targeted view updates: only the `SeekBar`, two time labels and the cover ring subscribe to position; the grid and transport row observe only `current`/`isPlaying`.
6. **Persist the same schema, one key, tolerant parser**: `{currentStoryId, positionMs, queueIds:[id], timer, updatedAt}`, flushed ~every 5 s and on `onStop`, **never before hydration**, and **never overwriting user actions taken during restore** — both guards were bug fixes and must survive as explicit tests.
7. **Restore must not autoplay** (RN behavior, deliberate and user-tested).
8. **Keep queue ids-only, de-duplicated, reorderable with up/down (no drag-and-drop)**, and keep the asymmetry decision explicit: **"Play now" clears the queue**, `⏭` **wraps the catalog**, **auto-advance at story end does not** (stops when the queue is empty). Confirm each of these as intended in the proposal (§ Open questions Q2/Q3).
9. **Sleep timer: 15/30/45 min + "end of this story" + off**; expiry **pauses and preserves the queue** (never clears it). Fix the two known flaws: store remaining duration or use `SystemClock.elapsedRealtime`/`AlarmManager` so it survives screen-off, and decide whether a minutes timer should carry across an auto-advanced track. `end_of_story` must keep suppressing auto-advance.
10. **Theme is flat-by-design for hardware reasons**: exact tokens `#17152E / #28244B / #72E0B8 / #FFB66E / #F8F7FF / #AAA3CE`, extra literals `#3D3860`, `rgba(170,163,206,0.4)`, `rgba(114,224,184,0.28)`; **no elevation/shadow anywhere**, depth expressed by surface-on-background. Any "prettier UI" must add polish through color, gradients, spacing and typography — not shadows/blur/animation.
11. **Cover progress is a square ring, not an arc** (`COVER_CORNER_RATIO 0.12`, 3 border segments lighting at 2 %/28 %/55 %/80 %, peach outer ring when the timer is active). Reproduce it properly with a single `Drawable`/`onDraw` four-side path instead of the RN border-hack, and keep the peach timer state.
12. **Typography is 3 faces only** (Fredoka SemiBold for display, Nunito Sans Regular/Bold for body) at the size ladder in §7; ship them in `res/font` and define matching `TextAppearance`s so no screen uses the system font.
13. **Time strings follow `formatTime` (`m:ss`, `h:mm:ss` past an hour) and `formatRemaining` (`ceil`, `m:ss`)** — every story is 21–52 min so the player is practically always `m:ss`; keep the ±15 s transport and the 52 dp minimum touch targets (Fire 10" at 1.5 density makes 52 dp ≈ 78 px, comfortable).
14. **Two catalog orders exist** — alphabetical (`catalog.ts`, drives "next in catalog") vs publication (`cuentos.tsv`). Pick one deliberately and document it; also decide whether to re-sort by duration/age for a 5-column grid.
15. **Layout must be genuinely dual-orientation**: the RN split landscape layout (`w ≥ 600 dp && w/h ≥ 1.15` → cover left, controls right, capped at 520 dp, no scroll) is the correct model for a 1280×800 dp Fire held sideways, and the 5-column catalog follows `widthDp/272`. Implement with `values-w600dp-land` layouts + `RecyclerView` `GridLayoutManager(count = widthDp/272)` rather than runtime branching.
16. **No navigation params**: every screen reads shared state. In Kotlin that means one Activity + Fragments on a nav graph with a shared `ViewModel`, or a single Activity swapping layouts; `Player` with no current story shows an empty state with a "back to catalog" action, and `Queue`/`Timer` are reachable only from the player in the current UX.
17. **Content fixes ride along**: `bambi` description typo, `los-increibles` accents, `fechaPublicacion` unused (drop or display), and the misleading queue empty-state copy.

## Open product questions

1. **Package/signing:** keep `com.julicuentos.app` (requires uninstalling the existing RN build, different signing key, and loses the stored queue/position) or install alongside as e.g. `com.julicuentos.native`? Do we want to *import* the old AsyncStorage JSON if both are present?
2. **"Play now" wipes the queue** — keep that, or make it "queue the rest after this one"? (A child tapping a new cover currently loses the whole playlist.)
3. **End of queue:** stop (current auto-advance behavior) or wrap to the next catalog entry like the `⏭` button? Should a **previous** button be added (currently none exists)?
4. **Sleep timer:** should it pause only at the end of the current story, or cut mid-sentence (current behavior)? Should the countdown survive screen-off/reboot, and should the 15/30/45 options be different for bedtime (e.g. 10/20/30)?
5. **"Prettier UI" scope:** do we keep the "Noche de cuentos" flat dark-violet/mint system exactly (§7), or add a distinct kid-facing style — bigger covers, fewer rows, per-genre color, illustrated empty states? Any reference the daughter/owner likes?
6. **Grid metadata:** keep 5 columns in landscape, or go larger cards (3 columns, 1920×1200 is forgiving) — and should the grid show duration (`26:44`) and/or the 2-line synopsis it shows today?
7. **Content curation:** is alphabetical order acceptable for a child picking by picture, or should it be by publication/duration/"most listened"? Any favorites/"continue listening" row wanted (nothing like it exists today)?
8. **Notification/lock-screen set:** which controls are required (play/pause, ±15, next, timer state, artwork)? Is a persistent "Now playing" notification acceptable on the Fire's launcher?
9. **APK size vs install path:** confirm ~700 MB APK is OK for the device's free storage and that `adb --no-local install` is acceptable, versus shipping audio in `obb`/expansion or copying it to app-private storage on first launch.
10. **Are the 20 stories final**, and will new stories be added by re-running a generator (keep the `content/<id>/` + TSV convention and emit a `stories.json` asset) or hand-edited Kotlin? Should the app survive an APK update that changes ids (unknown ids are currently dropped silently)?
11. **Extras wanted now that we own the platform:** sleep fade-out instead of hard pause, per-story resume vs restart, "repeat this story" loop, kid-lock (block exit / hide system bars), headphone-unplug behavior (pause — AudioFocus loss already implies it), and whether `AudioManager` should duck on notifications.
12. **Language:** keep all copy in Spanish (current) — confirm the strings to reuse vs rewrite for the polished UI.
