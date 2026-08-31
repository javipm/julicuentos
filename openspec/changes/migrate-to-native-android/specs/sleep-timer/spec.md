# Sleep Timer Specification

## Purpose

Bedtime cutoff for playback: off, fixed minutes (15/30/45), or "at the end of this story". The countdown must be exact across screen-off (the RN 1 Hz JS interval drifted/stopped when backgrounded) and expiry must be gentle — a ~10 s volume fade into pause instead of a hard cut. The queue is always preserved on expiry.

## Behavior delta vs the RN app (must survive design and tasks)

**Timer expiry fades instead of hard-pausing.** In the RN app, expiry called `pauseAsync()` on the tick — an instant cut mid-sentence. In the native app, when a minutes timer reaches zero, the volume MUST fade to silence over ~10 s and then pause. The queue is still preserved on expiry (parity). `end_of_story` continues to suppress auto-advance.

## Requirements

### Requirement: Timer modes

The timer MUST support exactly three modes: `{kind: "off"}`, `{kind: "minutes", minutes: 15|30|45, endsAt}`, and `{kind: "end_of_story"}`. Any other minutes value or a `minutes` mode without a valid `endsAt` is invalid input and MUST normalize to `off` (tolerant restore — see Persistence spec). Only one mode is active at a time.

#### Scenario: Mode set is single-select

- GIVEN the timer screen is open with "Desactivar" currently selected
- WHEN the user selects "30 minutos"
- THEN the timer becomes a 30-minute minutes-timer and no other mode is active.

### Requirement: Timer selection UI

The timer screen MUST show: back (✕), title **"Temporizador"** (Fredoka 20), helper copy **"Al vencer, se pausa la reproducción y se conserva la cola."**, and five single-select options in 56 dp rows with 16 dp radius: **15 minutos**, **30 minutos**, **45 minutos**, **"Al terminar este cuento"**, **"Desactivar"**. Selected option: solid `#FFB66E` background with `#17152E` bold text; unselected: `#28244B` background with `#F8F7FF` text. Choosing any option MUST apply it immediately and return to the previous screen (no confirm button).

#### Scenario: Selecting a timer applies and returns

- GIVEN a story is playing and the timer screen is open
- WHEN the user taps "30 minutos"
- THEN the option highlights as selected and the screen returns immediately, with the player now showing the timer countdown line.

### Requirement: Minutes countdown (screen-off-proof)

A minutes timer MUST be anchored to `SystemClock.elapsedRealtime()` (monotonic, unaffected by screen-off or wall-clock changes), NOT to wall-clock `Date.now()` and NOT to a JS-style interval that dies when the app is backgrounded. The countdown display MUST tick at 1 Hz and remain correct across screen off/on. `endsAt` is persisted (see Persistence spec) so the timer survives process death.

**Acceptance criteria:** with the screen off, a 15-minute timer set at T expires at T+15:00 with ≤ 5 s drift, verified on the Fire HD 10.

#### Scenario: Countdown survives screen-off

- GIVEN a story is playing and the user selects the 15-minute timer
- WHEN the screen is turned off
- THEN when the screen is turned back on at minute 6, the timer line shows ≈ "9:00" remaining (not a frozen or restarted value), and the timer still expires at T+15:00.

#### Scenario: Timer display format

- GIVEN a minutes timer is active with 9 min 59 s remaining
- WHEN the player is open
- THEN the timer line reads "Temporizador: 9:59" (formatRemaining: **ceil** to the next second, `m:ss`), updating once per second.

### Requirement: Expiry = ~10 s fade, then pause, queue preserved — **BEHAVIOR CHANGE**

When a minutes timer expires, the player MUST fade the volume from current level to silence over **≈ 10 s** (stepped fade is acceptable on API 22) and then pause (playing=false). The timer MUST then reset to `{kind:"off"}`. The queue MUST be preserved exactly as it was. There MUST be no hard cut and no clear of the queue. This replaces the RN instant pause.

#### Scenario: Fade-then-pause at expiry (device-observable)

- GIVEN a minutes timer is about to expire while a story plays at normal volume
- WHEN the remaining time hits zero
- THEN over the next ~10 s the volume decreases stepwise to silence, then playback pauses, the timer line disappears, and the queue is unchanged (checked on the queue screen afterwards).

#### Scenario: Queue survives expiry

- GIVEN the queue is [B, C] and a minutes timer expires mid-story
- WHEN the fade completes and playback pauses
- THEN the queue still contains [B, C] and resuming playback continues the current story (the timer does not clear the queue, ever).

### Requirement: End-of-story timer suppresses auto-advance

When the timer mode is `end_of_story` and the current story ends naturally, the player MUST pause/stop at the end of that story, clear the timer to `{kind:"off"}`, and MUST NOT auto-advance to the queue head (the queue is preserved for the next session). The RN dead code `shouldPauseAtEndOfStory()` (true for both `end_of_story` and `minutes`, never wired up) MUST NOT be ported: a **minutes** timer expiry is handled by the fade, not by waiting for the story end.

#### Scenario: End-of-story pauses at the story boundary

- GIVEN the timer "Al terminar este cuento" is active and story A is playing with queue [B]
- WHEN story A reaches its natural end
- THEN playback stops (B does NOT auto-start), the timer line disappears, and the timer is off
- AND WHEN the user later taps play (or picks a story), B/C are still available in the queue.

#### Scenario: Minutes timer can cut mid-story (documented behavior)

- GIVEN a 15-minute timer is active on a 52-minute story (`rompe-ralph`)
- WHEN the timer expires 15 minutes in
- THEN the story fades out and pauses mid-story — the minutes timer cuts mid-story by design (bedtime), it does not wait for the story to end.

### Requirement: Timer visibility on the player

While a timer is active, the player MUST show a timer line in peach `#FFB66E` (Nunito Sans, 14): **"Temporizador: al terminar este cuento"** for `end_of_story`, or **"Temporizador: m:ss"** with the remaining time (ceil, 1 Hz) for a minutes timer. No timer line is shown when the timer is off. The timer state MUST also be reflected by the peach ring on the cover art (see Theme & Design spec).

#### Scenario: Timer line appears and disappears

- GIVEN a story is playing
- WHEN the user selects the 30-minute timer
- THEN the player shows "Temporizador: 29:59" counting down in peach
- AND WHEN the timer expires (fade → pause) or the user selects "Desactivar", the line disappears.
