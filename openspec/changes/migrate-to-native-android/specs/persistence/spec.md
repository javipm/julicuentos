# Persistence Specification

## Purpose

Exactly one persisted blob holds everything the player needs across cold starts: current story, position, queue, and timer. The RN app persisted the same shape to one AsyncStorage key; the native app persists one JSON string to a single `SharedPreferences` key with a tolerant parser. Two hard-won guards from the RN bug history must survive as explicit behavior: **never write before hydration** and **never overwrite a user action taken during restore**.

## Requirements

### Requirement: Single storage key, fixed JSON schema

Player state MUST be persisted under exactly ONE storage key (parity: `julicuentos.player.v1`) in `SharedPreferences`, as a single JSON object with this shape and nothing more:

```json
{
  "currentStoryId": "string|null",
  "positionMs": 0,
  "queueIds": ["id-1", "id-2"],
  "timer": { "kind": "off" | "minutes" | "end_of_story", "minutes": 15|30|45?, "endsAt": 0? },
  "updatedAt": 0
}
```

There MUST be NO per-story positions, no history, no favorites, no volume, no extra keys. `updatedAt` is written (timestamp) but never read (no TTL/expiry logic). There is exactly one global resume position for the whole app (no per-story resume positions — explicitly out of scope).

#### Scenario: Persisted blob matches the schema

- GIVEN stories have been played and a queue built
- WHEN the stored SharedPreferences value is inspected (adb or a debug dump)
- THEN it is one JSON object with exactly the fields `currentStoryId`, `positionMs`, `queueIds`, `timer`, `updatedAt` — and no other player-related keys exist in prefs.

#### Scenario: Single global resume position

- GIVEN story A is playing at 20:00
- WHEN the user opens story B and then force-stops the app
- THEN the cold restore resumes **B** at its last position (the single global position), and A's old position is not retained anywhere.

### Requirement: Flush cadence (5 s + onStop)

Persisted state MUST be flushed at least every **5 seconds** while the app is running and additionally on `Activity.onStop()` (covers screen-off, home, and app switch). Position updates flow from the player callback into the same snapshot; no extra per-position write timer is needed beyond the 5 s flush cycle. Writes MUST be tolerant of process death at any point (never leave the key half-written).

#### Scenario: Position survives a 5-second window

- GIVEN a story is playing
- WHEN the process is killed at any moment (e.g. `adb shell am kill` or a crash)
- THEN on next open the restore position is at most ~5 s behind where audio actually was when the process died.

#### Scenario: Screen-off flush

- GIVEN a story is playing
- WHEN the user turns the screen off (triggering onStop)
- THEN the current position/queue/timer are flushed immediately (verified by the cold-restore test within seconds, not waiting for the 5 s tick).

### Requirement: Never write before hydration

The persistence layer MUST NOT write anything until the initial restore (hydration) has completed. A restore that is still in flight MUST never be clobbered by an early default-state flush — this guard exists because an early write historically blanked the stored queue.

#### Scenario: Early lifecycle does not blank the stored state

- GIVEN a stored state with queue [B, C] and position 20:00
- WHEN the app cold-starts and the restore has not completed yet
- THEN no write occurs until hydration finishes; after hydration the stored queue/position are still [B, C] / intact even if the app is immediately backgrounded.

### Requirement: Never overwrite user actions taken during restore

If the user interacts with playback (play, pause, play-now, enqueue) **while the async restore is still in flight**, the in-flight restore MUST be discarded rather than clobbering the user's fresh state. User intent wins over the stale snapshot.

#### Scenario: User action during slow restore wins

- GIVEN a stored snapshot is being restored asynchronously
- WHEN the user taps a story (play-now) before hydration completes
- THEN the user's choice stands (that story plays), the queue reflects the user's actions, and the restore result is discarded — the restored position/queue do not overwrite what the user just did.

### Requirement: Cold restore is paused at the last position (success criterion c)

On cold start, the app MUST restore the last current story **paused at the last persisted position** (never autoplaying — the only non-autoplay load in the app), with the queue and timer restored. This MUST be verified by force-stop: kill the app from system settings while playing, reopen, and confirm the story is loaded paused at the last flushed position with the queue intact (success criterion c).

#### Scenario: Force-stop and reopen (success criterion c, device)

- GIVEN story A is playing at ~12:34 with queue [B, C] and a 30-minute timer active
- WHEN the app is force-stopped and reopened
- THEN the app opens on the catalog with the miniplayer showing A paused at the last persisted position (±5 s), the queue still [B, C], and NO audio playing
- AND WHEN the user opens the player and taps play, audio resumes from that position.

#### Scenario: Fresh install has clean defaults

- GIVEN a brand-new install (no stored state)
- WHEN the app opens
- THEN no story is current, no miniplayer is shown, the player shows the "No hay ningún cuento en reproducción." empty state, and no bogus write has occurred.

### Requirement: Tolerant parser with safe defaults

The persisted JSON MUST be parsed defensively (validator-first): bad/truncated JSON, wrong types, or missing fields MUST yield safe defaults instead of a crash — `currentStoryId: null`, `positionMs: 0`, `queueIds: []`, `timer: off`. Specifically: a non-numeric/NaN/negative `positionMs` → 0; non-string `queueIds` entries filtered out; `timer.minutes ∉ {15,30,45}` or a missing/invalid `endsAt` → `{kind:"off"}`; `end_of_story` and `off` accepted as-is.

#### Scenario: Corrupt JSON degrades to defaults

- GIVEN the stored value is `"not json{"` (or any unparseable string)
- WHEN the app cold-starts
- THEN it behaves exactly like a fresh install (nothing current, empty queue) and the next flush overwrites the bad blob with valid JSON.

#### Scenario: Invalid timer value normalizes to off

- GIVEN a stored state whose `timer` is `{kind:"minutes", minutes: 37, endsAt: <past>}`
- WHEN the app restores
- THEN the timer is treated as off (no countdown line, no fade pending), the story/queue still restore, and no crash occurs.

#### Scenario: Expired minutes timer does not self-pause on restore

- GIVEN the app was backgrounded with a 15-minute timer whose `endsAt` has already passed
- WHEN the app is reopened (restore is paused by definition)
- THEN the app does NOT play-then-pause itself within a second (the RN flaw); the expired timer is simply discarded to off while story, position, and queue restore normally.

### Requirement: Restore drops unknown ids

On restore, a `currentStoryId` that does not exist in the compiled-in catalog MUST be dropped (state → Idle, no miniplayer), and unknown queue ids MUST be filtered out (see Queue spec). Known ids are restored unchanged, in order.

#### Scenario: Unknown current story is dropped

- GIVEN the stored `currentStoryId` is "historia-borrada" (not in the 20-story catalog)
- WHEN the app cold-starts
- THEN there is no current story, no miniplayer, and the (known) queue ids still restore and play.
