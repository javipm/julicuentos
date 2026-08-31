# Queue Specification

## Purpose

An ordered, ids-only playback queue with append, de-duplicate, reorder, remove, and clear operations, plus the next-story resolution rule shared by the ⏭ button and auto-advance. The queue is the child's built-up playlist; protecting it is a product priority, which is why "play now" changes behavior in this port.

## Behavior deltas vs the RN app (must survive design and tasks)

1. **"Reproducir ahora" no longer clears the queue.** In the RN app `playStoryNow` wiped the queue (`playNowClearsQueue() → []`), letting one tap destroy a built-up playlist. In the native app, play-now **preserves the queue** and only replaces the current story. (Proposal § Locked decisions 6.)
2. *(Related, specified in Sleep Timer spec.)* Timer expiry **fades out ~10 s** instead of pausing instantly, and still preserves the queue.

Everything else is parity: de-duplicated append, up/down reorder without wraparound, remove, clear, queue-head auto-advance, empty-queue stop, circular ⏭.

## Requirements

### Requirement: Ids-only queue model

The queue MUST be an ordered list of story ids (`string`), never copies of story objects; story data is always resolved from the compiled-in catalog by id. Unknown ids (e.g. from a catalog edit between versions) MUST be dropped silently when the queue is materialized or restored — the queue self-heals rather than crashing.

#### Scenario: Queue derives from catalog

- GIVEN the queue contains ids [B, C]
- WHEN the queue screen is opened
- THEN rows render with the catalog data (cover, title) for B and C, in order.

#### Scenario: Unknown ids are dropped safely

- GIVEN a persisted queue containing id "story-que-no-existe"
- WHEN the app restores the queue
- THEN the unknown id is dropped, the remaining ids render normally, and no crash or blank row occurs.

### Requirement: Enqueue is append + de-duplicated

`enqueue(id)` MUST append the id at the end of the queue; if the id is already present, the queue MUST be returned unchanged (no duplicates, no reordering, no moving to head/tail). Enqueueing the current story MUST be possible (it is how the "＋" button works) — the queue entry simply waits.

#### Scenario: Enqueue appends

- GIVEN the queue is [B]
- WHEN the user adds C, then D
- THEN the queue is [B, C, D].

#### Scenario: Enqueue is idempotent

- GIVEN the queue is [B, C]
- WHEN the user adds B again (from the ⋮ sheet or the player's ＋)
- THEN the queue is still [B, C] (one B, still in position 1) with no duplicate and no reorder.

#### Scenario: Player + button reflects membership

- GIVEN story C is playing and C is already in the queue
- WHEN the player is open
- THEN the "＋" (add to queue) control is disabled and shown as a mint ✓ instead of ＋
- AND WHEN C is removed from the queue, the player's button returns to "＋".

### Requirement: Reorder and remove

The queue screen MUST offer, per row: **move up**, **move down**, and **remove** (48×48 buttons inside ≥ 52 dp rows; remove tinted peach `#FFB66E`). `moveUp`/`moveDown` swap adjacent ids and are **no-ops at the boundaries** (no wraparound: the first row cannot move up, the last cannot move down). Removal MUST filter by id. "Vaciar" clears the whole queue (see Queue UI requirement for its enable/disable rule). No drag-and-drop is required.

#### Scenario: Move up/down swaps neighbors

- GIVEN the queue is [A, B, C]
- WHEN the user taps up on C
- THEN the queue becomes [A, C, B]
- AND WHEN the user taps up on A (already first), the queue is unchanged.

#### Scenario: Remove from queue

- GIVEN the queue is [A, B, C]
- WHEN the user taps ✕ on B
- THEN the queue becomes [A, C], and if B was NOT the currently playing story, playback is unaffected.

### Requirement: Play-now preserves the queue — **BEHAVIOR CHANGE**

Starting a different story via "Reproducir ahora" (catalog card tap or sheet action) MUST set it as current and start playback at 0:00 **while preserving the existing queue**. The RN behavior of clearing the queue on play-now MUST NOT be ported. A child tapping a new cover can no longer destroy a built-up playlist.

#### Scenario: Play-now keeps the built playlist

- GIVEN the queue contains [B, C] and story A is playing
- WHEN the user taps "Reproducir ahora" on story D's card (or taps D's card body)
- THEN D plays from 0:00, and the queue is still [B, C] (verified on the queue screen).

#### Scenario: Add-to-queue and play-now compose

- GIVEN the queue is empty
- WHEN the user adds B and C via ⋮, then taps play-now on D
- THEN D plays and the queue is [B, C]; when D ends, B plays, then C (standard auto-advance).

### Requirement: Next resolution and end-of-queue

The next story resolution MUST be: queue head if the queue is non-empty (consuming it), otherwise the circular next id in the **alphabetical catalog** order. At natural end of story with an empty queue, playback MUST stop — the queue rule's asymmetry lives here (see Playback spec for the paired requirement).

#### Scenario: Queue head wins over catalog order

- GIVEN the queue is [C] and story A is playing
- WHEN A finishes (or ⏭ is tapped)
- THEN C plays next — not the alphabetical successor of A.

#### Scenario: End-of-queue stops

- GIVEN the queue is empty and story A finishes naturally
- WHEN the end-of-story handler runs
- THEN playback stops, no story auto-starts, and the queue remains empty.

### Requirement: Queue screen

The queue screen MUST show: header with back (✕), title **"Cola"** (Fredoka 20), and a **"Vaciar"** text action — peach `#FFB66E` and enabled when the queue is non-empty, soft-purple/disabled when empty. Each row: surface `#28244B`, radius 16, min height 72 dp, 56×56 cover with 10 dp radius, bold title (2 lines), and up/down/remove buttons. The empty state copy MUST be corrected from the RN text (which misleadingly pointed at the card body): it must direct users to the real add affordances — the card **⋮ menu ("Añadir a la cola")** and the player's **＋** button. Suggested copy: "La cola está vacía. Añade cuentos desde el menú ⋮ de una tarjeta o con el botón + del reproductor."

#### Scenario: Empty queue copy names the real affordances

- GIVEN the queue is empty
- WHEN the queue screen is opened
- THEN the empty state mentions adding via the ⋮ card menu or the player's ＋ button (not "tap a card", which is play-now), and "Vaciar" is visibly disabled.

#### Scenario: Full queue round-trip on device

- GIVEN stories B, C, D added to the queue while A plays
- WHEN the user reorders (D up), removes one (C), and finally taps "Vaciar"
- THEN each action is reflected immediately in the list and in subsequent auto-advance behavior, with touch targets never below 52 dp.
