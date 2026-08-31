# Playback Specification

## Purpose

Owns the audio engine, playback state machine, transport controls, seeking, background playback, the media notification, and audio-focus behavior. This capability fixes the two headline bugs of the RN app: screen-off playback death (no foreground service / wake lock existed) and the continuous-seek seek bar. All audio is bundled (`assets/audio/<id>.mp3`); there is no streaming, no recovery layer, and no network.

## Requirements

### Requirement: Single ExoPlayer source of truth

Playback MUST run through exactly one Media3 `ExoPlayer` instance owned by a `MediaSessionService`, exposed app-wide through a single playback repository/state holder. The UI MUST have exactly one observable playback state — an explicit state machine with the states **`Idle`, `Loading`, `Ready`, `Playing`, `Paused`, `Ended`, `Error`** — and MUST NOT derive playback state from several loosely-consistent booleans. At most one audio stream may be audible at any time.

#### Scenario: State machine drives the player UI

- GIVEN a story is started from the catalog
- WHEN the audio is being prepared
- THEN the player shows the mint loading indicator with "Cargando audio…"
- AND WHEN playback actually starts
- THEN the loading indicator disappears, the play button shows the pause glyph, and the state is `Playing`
- AND WHEN the user taps pause
- THEN the state is `Paused` and the button shows play.

#### Scenario: Exactly one audio source

- GIVEN story A is playing
- WHEN the user starts story B (play now, queue advance, or next)
- THEN story A's audio is released/stopped before story B is prepared, and only one audio stream is audible at any moment
- AND WHEN a second load is requested while the first load is still in flight
- THEN the first load's callbacks are discarded and no second stream is ever heard.

### Requirement: Load-story contract

Loading a story MUST follow this observable contract: (1) select the new current story and update UI state first, (2) release/stop the previous audio source **before** preparing the new one, (3) start playback only if the load was requested with autoplay, (4) discard any stale completion/error events from a superseded load. A load MUST NOT produce overlapping audio from two sources. Catalog→player navigation MUST NOT block on the audio prepare step (the player screen paints first, loading state shown meanwhile).

#### Scenario: Switch stories produces no overlap

- GIVEN story A is playing at 10:00
- WHEN the user taps play-now on story B
- THEN A's audio stops before B becomes audible, B starts from 0:00, and no frame of A is audible after B starts.

#### Scenario: Rapid story switching stays sane

- GIVEN story A is loading
- WHEN the user immediately (within the loading window) taps play-now on story C
- THEN only story C plays; A never starts, and the player shows C as current with no leftover loading state from A.

### Requirement: Play/pause and transport

The player MUST provide play/pause toggle and **±15 s skip** buttons. `skipBy(-15_000)` and `skipBy(+15_000)` MUST clamp to `[0, duration]`. There MUST be **no previous-track button, no shuffle, no repeat, no speed control, no chapter list**.

#### Scenario: Skip backward clamps at zero

- GIVEN story A is playing at 0:08
- WHEN the user taps −15 s
- THEN playback continues from 0:00 (never a negative position, never an error).

#### Scenario: Skip forward mid-story

- GIVEN story A (duration 26:44) is playing at 12:00
- WHEN the user taps +15 s
- THEN playback continues from approximately 12:15 (±1 s) and the time labels update immediately.

### Requirement: Seek = drag-preview + commit-on-release

The seek bar MUST implement drag-preview + commit-on-release, replacing the RN continuous-seek behavior:

- On drag start: dragging mode begins; incoming playback positions MUST stop moving the thumb.
- During drag: the thumb tracks the finger and a live target-time label (formatted `m:ss`/`h:mm:ss`) is visible and updates; **the player MUST NOT be touched** — no seek, no reload, per move event or otherwise.
- On release: **exactly one** `seekTo(target)` is committed and playback continues from the committed position; the thumb lands where the finger left the bar (local asset audio makes the seek effectively instant).
- The seek bar MUST NOT be placed inside a scrolling container, so a scrub is never stolen by a scroll gesture in portrait or landscape layouts.
- Time labels around the bar MUST follow `formatTime` (current) and real duration.

This is a behavior fix vs the RN app, which seeked on every touch event and could reload audio mid-drag.

#### Scenario: Thumb never snaps backwards (success criterion b, part 1)

- GIVEN a story is playing on the Fire HD 10
- WHEN the user grabs the thumb and drags it slowly across the bar
- THEN the thumb tracks the finger with no backward snapping at any point during the drag.

#### Scenario: Live preview and single commit (success criterion b, part 2)

- GIVEN a story with duration 26:44 is playing at 05:00
- WHEN the user drags the thumb to the 20:00 mark and holds it
- THEN a target label reading "20:00" (±1 s of the finger position) is visible while dragging
- AND WHEN the user releases
- THEN exactly one seek is committed, playback resumes from ≈20:00, and the thumb stays there (no jump back to 05:00).

#### Scenario: Tap-to-seek commits once

- GIVEN a story is playing at 05:00
- WHEN the user taps the middle of the seek bar once
- THEN exactly one seek to the tapped position is committed and the thumb rests at the tapped position.

#### Scenario: Scrubbing is never stolen by scrolling (success criterion b, part 3)

- GIVEN the player is open in portrait (scrollable body) and in landscape (split layout)
- WHEN the user performs a scrub in each orientation, including diagonally started drags
- THEN the seek gesture completes on the bar every time and the enclosing view never scrolls the gesture away or interrupts the drag.

### Requirement: Autoplay rules

Every explicit user action that starts a story MUST autoplay: play-now, resume (tap on paused current story), ⏭ next, queue auto-advance, and retry after an error. The ONLY load that MUST NOT autoplay is the cold-start restore (see Persistence spec: restores paused at the last position).

#### Scenario: Every user action autoplays

- GIVEN the app is running
- WHEN the user plays a story from the catalog, taps ⏭, or taps ▶ after an error
- THEN audio starts playing in each case without a further tap.

#### Scenario: Cold restore does not autoplay

- GIVEN the app was force-stopped while a story was at 12:34
- WHEN the app is reopened
- THEN the restored story is loaded paused at 12:00-range position and **no sound plays** until the user taps play.

### Requirement: Duration from real metadata

The displayed duration and seek range MUST come from the player's real media metadata once available. The catalog `duracionSegundos` MAY be shown as the initial placeholder (formatted from the TSV value) but MUST be replaced by real metadata after load. A story whose real duration differs from its catalog duration MUST seek/scrub against the real duration.

#### Scenario: Seek bar range uses real metadata

- GIVEN the catalog says `cars-2` is 40:11 but the actual MP3 is a few seconds different
- WHEN the story is loaded
- THEN the seek bar range and end label reflect the real audio duration, not the catalog value, once metadata is parsed.

### Requirement: End of story vs manual next (queue asymmetry)

At natural end of story, the player MUST advance to the **queue head only**; if the queue is empty, playback MUST **stop** (state `Ended`, playing=false, empty queue preserved) and MUST NOT wrap to the next catalog story. The manual ⏭ button (player transport and miniplayer) resolves next as: queue head if any, else the **circular next story in the alphabetical catalog** — ⏭ wraps, auto-advance does not.

#### Scenario: Auto-advance takes queue head

- GIVEN story A is playing and the queue is [B, C]
- WHEN story A finishes naturally
- THEN B starts immediately from 0:00 with autoplay, and the queue becomes [C].

#### Scenario: Auto-advance stops at empty queue (no wrap)

- GIVEN story A is playing and the queue is empty
- WHEN story A finishes naturally
- THEN playback stops (no new story starts, no notification that suggests otherwise), and the next story in alphabetical order does NOT auto-start.

#### Scenario: Manual next wraps the catalog

- GIVEN the queue is empty and the last story alphabetically (`toy-story`) is playing
- WHEN the user taps ⏭
- THEN the first story alphabetically (`101-dalmatas`) starts from 0:00.

### Requirement: Background playback (success criterion a)

While audio plays, the app MUST run a foreground service with `foregroundServiceType="mediaPlayback"` holding a wake lock, so playback continues with the screen off. This MUST be verified on the real Fire HD 10 (not only an emulator): starting a story and turning the screen off, playback MUST continue **≥ 30 minutes** (at least one full story end-to-end) without the process being killed or audio stopping.

#### Scenario: 30-minute screen-off playback (success criterion a)

- GIVEN a ~52-minute story (`rompe-ralph`) is playing on the Fire HD 10
- WHEN the screen is turned off and left off for ≥ 30 minutes
- THEN audio is still playing when the screen is turned back on, at a position consistent with uninterrupted playback, and the app process was never killed.

#### Scenario: Playback survives screen-off/lock repeatedly

- GIVEN a story is playing
- WHEN the user turns the screen off and on again several times during playback
- THEN audio continues after each screen-off without restarts, gaps, or a dead player UI.

### Requirement: Media notification and media buttons

While a story is loaded, the app MUST post a media notification with artwork (story cover), title, and the actions **play/pause, −15 s, +15 s, next**. The notification MUST reflect the real playing/paused state and be functional from the lock screen / shade on Fire OS 5.3 (API 22: no notification channels — channel code must be O(26)+ guarded yet harmless on 22). Hardware/media-button play/pause and next MUST control playback. If the Media3 session notification misbehaves on API 22, the fallback is `NotificationCompat.MediaStyle` from `androidx.media` (decided in design, but the requirement — working play/pause/±15/next + artwork — is fixed).

#### Scenario: Notification controls work (device)

- GIVEN a story is playing and the screen is off
- WHEN the user taps play/pause, ±15 s, and next on the notification / lock screen
- THEN playback responds to each action correctly and the artwork shown is the current story's cover.

#### Scenario: Notification state stays in sync

- GIVEN a story is playing
- WHEN the user pauses from the notification
- THEN the notification icon switches to play, the player screen (if reopened) shows Paused, and audio is silent
- AND WHEN the user resumes from the notification
- THEN audio continues from the paused position.

### Requirement: Audio focus

The player MUST request audio focus when playing and MUST handle loss: on **permanent loss** (`AUDIOFOCUS_LOSS`) → pause and do not auto-resume; on **transient loss** (e.g. navigation prompt) → pause and resume automatically when focus returns if it was playing; on **transient-can-duck** loss (e.g. notification sound) → duck volume briefly without pausing. Unplugging headphones is expected to surface as a focus/route change that pauses rather than blasting the speaker.

#### Scenario: Pause on focus loss

- GIVEN a story is playing
- WHEN another app takes audio focus permanently
- THEN Julicuentos pauses and stays paused until the user presses play again.

#### Scenario: Duck on transient notification sound

- GIVEN a story is playing
- WHEN a short notification sound plays (transient-can-duck)
- THEN the story volume dips and then restores automatically without pausing.

### Requirement: Error handling (bundled audio only)

Error handling collapses to "audio file missing/unreadable": a failed load or decoder error MUST surface the `Error` state with a Spanish message and a **"Reintentar"** action that retries the same story at the attempted position, plus **"Cerrar"**. There MUST be no streaming-recovery logic (no stall watchdog, no retry counters, no skip-ahead heuristics), no CORS/web branches, and no network error copy.

#### Scenario: Missing audio file degrades gracefully

- GIVEN a story whose MP3 is absent/corrupt (test build with one asset removed)
- WHEN the user plays it
- THEN the player shows an error card in Spanish with "Reintentar" and "Cerrar" (both ≥ 52 dp), the rest of the app stays functional, and no crash occurs
- AND WHEN "Reintentar" is tapped after restoring the file, the story plays normally.

### Requirement: Progress updates are targeted, not global

Position-driven UI (seek bar, time labels, cover ring, miniplayer strip) MUST update at a bounded cadence (~2 Hz / every 500 ms on this hardware) without rebuilding the catalog grid or re-binding cards: the grid observes only current story / playing state. This ports the RN performance intent into targeted view updates.

#### Scenario: Grid stays still while progress ticks

- GIVEN the catalog with the miniplayer visible and a story playing
- WHEN the user watches the grid for 30 s of playback
- THEN grid cells do not rebind or flicker (only the miniplayer strip animates), and scrolling the grid while playing shows no frame-time collapse.
