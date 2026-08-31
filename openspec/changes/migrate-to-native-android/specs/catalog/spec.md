# Catalog Specification

## Purpose

The catalog is the home screen: the compiled-in list of the 20 bundled stories, browsable as an adaptive grid, with per-story actions and a miniplayer. It replaces the RN `app/index.tsx` screen 1:1 except where this change deliberately differs (duration chip added, empty-catalog state deleted, play-now keeps the queue — see Queue spec).

## Requirements

### Requirement: Compiled-in catalog data

The app MUST ship the full story catalog compiled into the APK (Kotlin constant list or a `stories.json` asset), containing **exactly 20 stories** sorted **alphabetically by `id`** (`101-dalmatas, aladdin, alicia-en-el-pais-de-las-maravillas, bambi, blancanieves-y-los-siete-enanitos, brave, buscando-a-nemo, cars-2, dumbo, el-libro-de-la-selva, el-rey-leon, enredados, la-cenicienta, la-sirenita, los-increibles, monstruos-s-a, peter-pan, pocahontas, rompe-ralph, toy-story`). Each entry MUST carry `id` (equals the `assets/audio/<id>.mp3` file name), `titulo`, `descripcion` (may be empty), `duracionSegundos` (integer ≥ 0, from the source TSV), a `thumbnail` (512 px grid image) and a `cover` (player/miniplayer image). The field `fechaPublicacion` MUST NOT exist in the native data model (it was never displayed).

Accepted: the catalog MUST NOT load from network and MUST NOT be user-extendable at runtime; the empty-catalog state of the RN app ("Añade carpetas en content/<id>/…") MUST NOT exist.

**Acceptance criteria**
- Cold start with airplane mode on shows the 20-story grid (no network needed, ever).
- Catalog order is alphabetical by id; `playNext`-style circular resolution uses this exact order.
- Content fixes ride along: the `bambi` description no longer starts with the truncated word `"ambi es un pequeño cervatillo…"`, and the `los-increibles` title displays the accented form "Los Increíbles".

#### Scenario: Catalog renders completely offline

- GIVEN the Fire HD 10 (or emulator) with the APK installed and airplane mode on
- WHEN the app cold-starts
- THEN the catalog screen shows all 20 stories as a grid, each with title, 2-line synopsis (or fallback "Cuento" when the description is empty), duration chip, and thumbnail
- AND no error, empty, or "add content" state is ever shown.

#### Scenario: Fixed content quirks

- GIVEN the bundled catalog data
- WHEN the `bambi` card is inspected
- THEN its description is a complete Spanish synopsis (not `"ambi es un pequeño cervatillo…"`)
- AND WHEN the `los-increibles` entry is displayed
- THEN the title reads "Los Increíbles" (with accent).

### Requirement: Adaptive grid columns

The catalog MUST render as a grid whose column count follows the logical width: **≥ 1024 dp → 5 columns, ≥ 768 dp → 3 columns, otherwise → 2 columns**. On the Fire HD 10 this yields 3 columns portrait and 5 columns landscape. Grid content padding MUST be 12 dp horizontal and 16 dp bottom when the miniplayer is visible, otherwise 32 dp bottom.

#### Scenario: Orientation changes column count

- GIVEN the catalog on the Fire HD 10 (~800 dp portrait width, ~1280 dp landscape width)
- WHEN the device is held portrait
- THEN the grid shows 3 columns
- AND WHEN the device is rotated to landscape
- THEN the grid re-lays out to 5 columns without a crash or lost scroll position.

#### Scenario: Small screen falls back to 2 columns

- GIVEN a narrow device or emulator profile with logical width < 768 dp
- WHEN the catalog is displayed
- THEN the grid shows 2 columns.

### Requirement: Story card composition

Each catalog card MUST show: a square cover/thumbnail image (aspect ratio 1:1), the title (Fredoka SemiBold, max 2 lines), a 2-line synopsis in soft text `#AAA3CE`, a **duration chip** with the story duration formatted `m:ss`, and a `⋮` overflow button (minimum 52×52 dp touch target, background `rgba(23,21,46,0.55)`). Cards use surface `#28244B`, corner radius 16 dp, on the `#17152E` background, with **no elevation or shadow**. While a story is the current one, its card MUST show a mint **"Sonando"** pill (background `#72E0B8`, text `#17152E` bold) overlaid on the image; the pill is hidden on all other cards. The pill and all card controls must remain flat (no elevation) on API 22.

#### Scenario: Duration chip is correct

- GIVEN the catalog is open
- WHEN any card is inspected (e.g. `el-libro-de-la-selva`, 1604 s)
- THEN the chip shows the catalog duration formatted as `m:ss` (e.g. "26:44") and matches the real MP3 duration within ±2 s.

#### Scenario: Sonando badge tracks the current story

- GIVEN story A is playing and the catalog is visible
- WHEN the user looks at cards
- THEN only story A's card shows the mint "Sonando" pill
- AND WHEN playback is switched to story B
- THEN the pill moves to story B's card (and the miniplayer title updates) without restarting the app.

### Requirement: Story action sheet (⋮)

Tapping a card's `⋮` MUST open a bottom sheet showing the story title (Fredoka 18sp) and three actions: **"Reproducir ahora"** (solid mint `#72E0B8`), **"Añadir a la cola"** (mint outline), **"Cancelar"** (soft text). Every row MUST be at least 52 dp tall with 16 dp corner radius. The sheet MUST appear over a dark scrim and dismiss on back, scrim tap, or "Cancelar" without side effects.

#### Scenario: Add to queue from the sheet

- GIVEN the ⋮ sheet is open for story B while story A plays
- WHEN "Añadir a la cola" is tapped
- THEN B is appended to the queue, the sheet closes, playback of A is uninterrupted, and B's id appears exactly once in the queue screen.

### Requirement: Row tap semantics (resolveOpenStoryAction)

Tapping a card body MUST resolve as follows (port of `resolveOpenStoryAction`):
- tapped story == current story **and playing** → open the player only; playback MUST NOT be interrupted or restarted.
- tapped story == current story **and paused** → resume playback and open the player.
- tapped story is different → start playing it from position 0 (autoplay) and navigate to the player **immediately, without waiting for the audio to load**; the queue MUST be preserved (behavior change vs RN — see Queue spec).

#### Scenario: Tap the currently playing story

- GIVEN story A is playing
- WHEN the user taps A's card body
- THEN the player opens showing A, playback continues uninterrupted (no reload, position not reset).

#### Scenario: Tap the current story while paused

- GIVEN story A is the current story and paused
- WHEN the user taps A's card body
- THEN playback resumes and the player opens.

#### Scenario: Tap a different story starts it without wiping the queue

- GIVEN the queue contains [B, C] and story A is playing
- WHEN the user taps story D's card body
- THEN D starts playing from 0:00, the queue still contains [B, C] (A untouched), and the player screen opens immediately while the audio is still loading
- AND THEN no duplicate of A or of the queue entries is created.

### Requirement: Miniplayer

The catalog MUST show an in-flow miniplayer footer (never an overlay) **only when a current story exists** (`currentStoryId != null`); it MUST be completely hidden otherwise. Layout: bar height 64 dp + a 6 dp progress strip + bottom inset (min 8 dp), background `#28244B`, top corners 12 dp. Contents: 48×48 cover with 8 dp corner radius, title (1 line), status line **"Sonando"** or **"En pausa"** (12 sp), a play/pause chip 52 dp (mint rounded square, corner ratio 0.22 — never a circle), and a next button (≥ 52 dp hit area) that resolves the next story as queue-head-else-circular-catalog and autoplays it. The progress strip MUST be non-interactive (visual only): track `#3D3860`, fill `#72E0B8`, at least 5 px tall.

**Acceptance criteria:** the miniplayer never overlays or pushes grid content off-screen (it is part of the scroll layout), and its progress strip ignores all touch gestures.

#### Scenario: Miniplayer visibility follows current story

- GIVEN a fresh install with nothing playing
- WHEN the catalog is open
- THEN no miniplayer is shown
- AND WHEN the user starts any story
- THEN the miniplayer appears with that story's cover, title and "Sonando".

#### Scenario: Miniplayer next button follows queue-then-circular rule

- GIVEN the queue contains [B] and story A is playing (A is followed by C in alphabetical catalog order)
- WHEN the user taps the miniplayer's next button
- THEN story B starts playing from 0:00
- AND GIVEN the queue is now empty and story B is playing
- WHEN the user taps next again
- THEN the next story in alphabetical catalog order after B starts playing (circular wrap).

### Requirement: Grid thumbnail budget (no OOM)

The grid MUST use the 512 px thumbnails (never the up-to-1400×1400 covers), decoded downsampled to view size with a bounded bitmap cache (≈ 8–16 MB LruCache or pre-baked downscaled assets). Full-size covers are reserved for the player and miniplayer. Opening the catalog in landscape (5 columns) MUST NOT exceed the ART heap or crash on the Fire HD 10.

#### Scenario: 5-column landscape catalog survives

- GIVEN the Fire HD 10 held in landscape with the catalog scrolled through all 20 stories
- WHEN all cards have been displayed
- THEN the app does not throw `OutOfMemoryError` and scrolling stays smooth (no visible jank on cell reuse).
