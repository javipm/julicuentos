# Theme & Design Specification

## Purpose

The "Noche de cuentos" visual system: the locked palette and fonts, the flat-on-API-22 constraint (a hardware requirement, not taste), 52 dp touch targets, dual-orientation layouts, time formats, the Spanish strings registry, and the square-ring cover progress. Depth comes from surface-on-background color, never from shadows.

## Requirements

### Requirement: Locked palette tokens

The app MUST define and use exactly these palette tokens (as color resources): background **`#17152E`** (fondo — screens, splash, text-on-accent), surface **`#28244B`** (cards, rows, miniplayer, error card, sheets), action/accent mint **`#72E0B8`** (play controls, progress fill, "Sonando", primary buttons), timer/peach **`#FFB66E`** (timer text and ring, "Vaciar", remove-from-queue), primary text **`#F8F7FF`**, soft text **`#AAA3CE`**. These code literals MUST also be ported explicitly (not derivable from the tokens): miniplayer progress track **`#3D3860`**, seek-bar track `rgba(170,163,206,0.4)`, cover base ring `rgba(114,224,184,0.28)`, card ⋮ button scrim `rgba(23,21,46,0.55)`, modal scrim `rgba(0,0,0,0.5)`. No screen may introduce colors outside this set.

#### Scenario: Every screen uses only locked colors

- GIVEN the app is installed
- WHEN each screen (catalog, player, queue, timer, sheets, dialogs, notification) is inspected on the device
- THEN all fills/text come from the token list above (spot-check: no greenish/blue/gray outside the six tokens + listed literals), the status bar is dark-themed over `#17152E`, and text never uses an off-palette color.

### Requirement: Flat by hardware constraint

The design MUST be completely flat on Android: **no elevation, no shadows, no blur, no animated vectors, no image fade animations on API ≤ 22, minimal overdraw**. This is a hardware requirement carried from the RN app's evidence (elevation rasterizes with jagged edges on API 22; alpha blending is costly on this SoC) — not a style preference. Depth is expressed by `#28244B` surfaces on the `#17152E` background. Rounded corners are fine; elevation attributes are not.

#### Scenario: Flat audit passes

- GIVEN the built app on the Fire HD 10 (or an API 22 emulator)
- WHEN every screen is inspected (cards, miniplayer, buttons, badges, sheets)
- THEN no view casts a shadow, no blur is rendered, no animated vector drawable runs, and cover images render with no fade-in (immediate draw).

### Requirement: Minimum touch targets 52 dp

Every interactive element MUST have a minimum touch target of **52×52 dp** (≈ 78 px on this device): ⋮ buttons, sheet rows, transport buttons, queue row buttons, seek interactions, "Ver cola", back buttons, notification-equivalent controls. The transport play button is 72 dp (64 dp in compact layouts); the miniplayer play/pause chip is 52 dp.

#### Scenario: Touch target audit

- GIVEN each screen (catalog, player, queue, timer, sheets) on the device
- WHEN interactive controls are measured (or layout-inspected via adb)
- THEN every tappable control measures ≥ 52 dp in both dimensions.

### Requirement: Bundled fonts and TextAppearances

The app MUST bundle exactly three font faces in `res/font`: **Fredoka SemiBold** (display) and **Nunito Sans Regular + Bold** (body), with fontFamily styles and `TextAppearance`s for the size ladder: 30 (screen title "Julicuentos"), 24 (player title), 20 (Cola/Temporizador headers), 18 (sheet title), 16 (body/miniplayer title), 14 (card description, labels, timer line), 12 (seek times, miniplayer status, badges). **No screen may render text in the system default font.** Fredoka SemiBold is used for: app title, card titles, player title, "Cola", "Temporizador", sheet title. Nunito Sans 400 for descriptions/statuses/time labels; Nunito Sans 700 for button labels, queue titles, the "Sonando" badge.

#### Scenario: No system font anywhere (device check)

- GIVEN the app is open on any screen (including the notification's title rendering from the app, and modal sheets)
- WHEN the text is inspected visually
- THEN display text is Fredoka SemiBold, body text is Nunito Sans — the Roboto/system font appears nowhere.

### Requirement: Dual-orientation player layouts

The player MUST implement both layouts from resource qualifiers (no runtime view surgery): **stack** for portrait (cover halo → title → description → seek → transport → "Ver cola", vertically centered in a scrollable body) and **split** for landscape when logical width ≥ 600 dp **and** w/h ≥ 1.15 (cover on the left, controls column capped at 520 dp on the right, gap 28, max content width 1100, **no scrolling**). On the Fire HD 10 landscape (≈1280×752 dp) the split layout MUST engage. Compact variants (short landscape) tighten spacing: section gap 12 (else 24), play button 64 (else 72), bottom padding 16 (else 32), description 1 line (else 2). Cover size: `0.72 × width` clamp [120, 340] (split: `0.42 × width`), leaving ≥ 320 dp for controls in split.

#### Scenario: Portrait stack layout

- GIVEN the Fire HD 10 in portrait (≈800×752 dp)
- WHEN the player is open
- THEN the layout is the vertical stack with the cover on top and the transport row at the bottom, scrollable if needed.

#### Scenario: Landscape split layout

- GIVEN the Fire HD 10 is rotated to landscape (w/h ≈ 1.7 ≥ 1.15, w ≥ 600 dp)
- WHEN the player is open
- THEN the cover renders on the left and the seek bar, transport, and "Ver cola" column sit on the right within ~520 dp, with no vertical scrolling required to reach any control.

### Requirement: Cover art square-ring progress

The player cover (and its halo) MUST be a **rounded square** (corner ratio 0.12 of its side), never a circle. Progress is a **square ring** with four segments lighting up at thresholds: top > 2 %, right > 28 %, bottom > 55 %, left > 80 % of the story duration; a base mint ring at `rgba(114,224,184,0.28)` alpha sits underneath at all times. When the sleep timer is active, a 3 dp peach `#FFB66E` outer ring appears around the cover. No SVG, no animation library, no reanimated-style per-frame work: one custom Drawable/onDraw path. Cover images draw with no fade on API ≤ 22.

#### Scenario: Ring segments light at thresholds

- GIVEN a 26:44 story (`el-libro-de-la-selva`) is playing
- WHEN the position is 1 % / 10 % / 40 % / 70 % / 95 %
- THEN the ring shows 0 segments, only top, top+right, top+right+bottom, all four segments respectively, around the square cover.

#### Scenario: Peach ring tracks the timer

- GIVEN any timer is active
- WHEN the player is visible
- THEN the cover has the peach 3 dp outer ring
- AND WHEN the timer is deactivated, the peach ring disappears.

### Requirement: Time formatting

All time strings MUST use: `formatTime(ms)` — floor to whole seconds, `h:mm:ss` when hours > 0, else `m:ss`; and `formatRemaining(ms)` — **ceil** to the next second, `m:ss`. Negative/NaN inputs clamp to 0:00. Stories run 21–52 min, so hours formatting is rare but MUST still be implemented (the 52-min story approaches it only if metadata exceeds an hour). Seek labels, miniplayer status timeline and the timer countdown all follow this rule.

#### Scenario: formatTime boundaries

- GIVEN durations/timestamps of 0 ms, 59_400 ms, 3_600_000 ms, and 3_090_000 ms
- WHEN they are formatted for display
- THEN they render as "0:00", "0:59", "1:00:00", "51:30" respectively.

#### Scenario: formatRemaining never shows 0:00 early

- GIVEN 400 ms remain on a timer
- WHEN the remaining time is formatted
- THEN it displays "0:01" (ceil), and it reaches "0:00" only at expiry.

### Requirement: Spanish strings registry

All user-facing strings MUST live in `values/strings.xml` in Spanish (informal-child register, capitalized eyebrow "REPRODUCIENDO", infinitive button labels), ported from the RN app verbatim where the copy is unchanged: "Julicuentos", "Elige un cuento y empieza a escuchar", "REPRODUCIENDO", "Reproducir ahora", "Añadir a la cola", "Cancelar", "Sonando", "En pausa", "Ver cola", "Vaciar", "Desactivar", "Reintentar", "Cerrar", "Temporizador", "Cola", "Cargando audio…", "No hay ningún cuento en reproducción.", "Volver al catálogo", "Al vencer, se pausa la reproducción y se conserva la cola.", the 15/30/45 min option labels, "Cargando audio…". No screen may hardcode strings in layouts/code. There is no i18n beyond Spanish.

#### Scenario: Strings come from the registry

- GIVEN the source tree
- WHEN layouts and composables-free view code are inspected
- THEN no user-facing text is hardcoded outside `strings.xml`, and the rendered app shows Spanish copy matching the list above (spot-check 5 screens).

### Requirement: Launch, splash and system chrome

The app MUST launch with a dark `#17152E` window/splash that holds until fonts resolve (no white flash, no system-font flash), a light status bar style over the dark background, and a launcher icon that is adaptive-icon-safe where supported with a static fallback that renders correctly on API 22. The launch screen and every screen background MUST be `#17152E`.

#### Scenario: Cold start shows branded splash

- GIVEN the app is launched from the launcher
- WHEN the process starts and fonts load
- THEN the user sees the dark `#17152E` splash (no white flash) before the catalog fades in with correct fonts.

### Requirement: Performance budget (API 22 hardware budget)

The UI MUST stay within the API 22 budget that forced the RN workarounds: flat solid fills only, no elevation/shadow on rounded controls, no blur, no animated vectors, no full-screen-layer invalidation at frame rate, minimal overdraw, recycled stable-id grid cells, shallow hierarchies, and **no seek bar inside a scrolling container**. Position-driven widgets update at ~2 Hz (500 ms) maximum cadence.

#### Scenario: Scrolling the catalog during playback stays smooth

- GIVEN a story is playing with the miniplayer visible
- WHEN the user fling-scrolls the 3-column portrait grid repeatedly
- THEN scrolling remains smooth (no dropped-frame storm), and CPU/GPU overdraw stays consistent with a flat design (no per-frame alpha/shadow work added by this change).
