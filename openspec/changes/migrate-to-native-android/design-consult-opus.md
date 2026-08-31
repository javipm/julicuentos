# Design consult — Julicuentos UI (Opus)

**Scope:** visual redesign deltas only. Read-only consult — no code changed.
**Target:** Amazon Fire HD 10 2015, API 22, landscape primary (~1280×752 dp usable), user = niña de 5 años.
**Constraints honoured:** flat only (no elevation / shadow / blur / heavy animation), locked palette, Fredoka SemiBold display + Nunito Sans body, all copy in Spanish, min touch 52 dp, framework `SeekBar` + `RecyclerView`.

---

## 0. New palette tokens (add to `values/colors.xml`)

Three additions. Everything else stays on the locked six.

| Token | Hex | Role |
|---|---|---|
| `superficieAlta` | `#332E5C` | Second flat surface step. Chips/controls that sit **on top of** a `superficie` panel and must still read as separate. One notch lighter than `#28244B` — depth by value, not by shadow. |
| `matte` | `#201C3D` | Letterbox mat behind a cover shown whole (`fitCenter`). Sits between `fondo` and `superficie`, so bands read as a deliberate passe-partout, not as a hole. |
| `borde` | `#3D3860` | Alias of the existing `track` value, used as a 1 dp hairline stroke on panels and the miniplayer. Named separately so a future track change doesn't move all borders. |

> Everything below assumes these three exist. If the owner rejects them: `superficieAlta`→`superficie`, `matte`→`fondo`, drop hairlines. The layout still works, it just reads flatter.

---

## Change list (prioritized)

12 changes. **P0 = biggest visual win.**

---

### C1 · P0 — Player landscape: two equal columns + a real control panel

**Problem:** in `layout-w600dp-land/fragment_player.xml` the right column is a bare `LinearLayout` of loose children on `#17152E`. Nothing groups them, so title/seek/transport/pill all float in black space.

**Delta:** make the split **symmetric** (two 560 dp columns, 32 dp gap, both vertically centred) and wrap the whole right column in a flat `#28244B` panel.

New/changed dimens (`values/dimens.xml`, landscape values are the ones that matter here):

```
<dimen name="player_cover_w">560dp</dimen>      <!-- was player_cover 340dp -->
<dimen name="player_cover_h">315dp</dimen>      <!-- 16:9 of 560 -->
<dimen name="player_land_gap">32dp</dimen>      <!-- was 28dp -->
<dimen name="player_panel_w">560dp</dimen>      <!-- was player_controls_width 520dp -->
<dimen name="player_panel_pad">28dp</dimen>
<dimen name="corner_panel">24dp</dimen>
```

New drawable `bg_panel.xml`:

```xml
<shape android:shape="rectangle">
    <solid android:color="@color/superficie"/>
    <stroke android:width="1dp" android:color="@color/borde"/>
    <corners android:radius="@dimen/corner_panel"/>
</shape>
```

Applied to `@id/player_content` (the right `LinearLayout`):
- `android:layout_width="@dimen/player_panel_w"`
- `android:background="@drawable/bg_panel"`
- `android:padding="@dimen/player_panel_pad"`
- keep `gravity="center_horizontal"`, keep `orientation="vertical"`.

Vertical rhythm **inside** the panel — replace the current ad-hoc `player_section_gap` / `player_desc_gap` / `player_transport_top_gap` / `player_pill_top_gap` mix with a strict 8 pt ladder. Values are `layout_marginTop` on each child, top to bottom:

| Element | marginTop | Notes |
|---|---|---|
| `player_title` | `0dp` | panel padding already gives 28 |
| `player_desc` | `8dp` | |
| `player_seek_block` | `24dp` | |
| `player_transport` (primary row) | `24dp` | |
| `player_chips` (new secondary row, see C3) | `16dp` | |
| `player_timer_line` | `16dp` | peach `#FFB66E`, 14 sp |

Total panel height ≈ 458 dp, cover 315 dp → both columns centred on the vertical axis of a 752 dp screen. No scrolling, unchanged.

Type inside the panel:
- `player_title`: Fredoka **28 sp** (`TextAppearance.Jc.28`, new rung), `#F8F7FF`, `maxLines="2"`, centred. *(Hero title — was 24 sp.)*
- `player_desc`: Nunito Regular **16 sp**, `#AAA3CE`, `maxLines="@integer/player_desc_max_lines"`, centred, `lineSpacingExtra="2dp"`.

**Close button:** `@id/player_back` currently floats naked at 0,0. Give it a home:
`android:layout_margin="16dp"`, `android:background="@drawable/bg_circle_surface"` (new: `<shape android:shape="oval"><solid android:color="@color/superficie"/></shape>`), size stays 56 dp, icon 28 dp `#F8F7FF` (was 52 dp — the glyph is oversized in the screenshot).

---

### C2 · P0 — Cover shown whole, 16:9, on a matte (kills the crop)

**Problem:** covers are 16:9 source art forced into a 340 dp square with `centerCrop` → Alicia loses her head-room, every cover is a different arbitrary crop.

**Delta:** the cover container becomes 16:9 and the art is `fitCenter` over a `matte` mat.

1. `CoverHaloView` in both player layouts: `layout_width="@dimen/player_cover_w"`, `layout_height="@dimen/player_cover_h"`.
2. `CoverHaloView.kt` — three mechanical edits (rect-aware, no new drawing cost):
   - `imageView.scaleType = ImageView.ScaleType.FIT_CENTER` (was `CENTER_CROP`).
   - `setBackgroundColor(ContextCompat.getColor(context, R.color.matte))` in `init`.
   - the outline provider and `onDraw` currently use `side = width` for both axes and `side * 0.12f` for the radius. Replace with:
     - `outline.setRoundRect(0, 0, view.width, view.height, dp(20f))`
     - in `onDraw`: `ringRect.set(inset, inset, width - inset, height - inset)`, `cornerRadius = dp(20f) - inset`
     - in `drawSegment`: `Top` → `clipRect(0, 0, width, height/2)`, `Bottom` → `clipRect(0, height/2, width, height)`, `Left` → `clipRect(0, 0, width/2, height)`, `Right` → `clipRect(width/2, 0, width, height)`.
   - Ring thickness unchanged (6 dp base `#4772E0B8` / mint `#72E0B8` segments; 3 dp peach `#FFB66E` timer ring).
3. Same treatment on the catalog cover — see C5.

Result: the ring becomes a **cinema frame** around a complete cover. Bigger, whole, and 65 % more cover area than today.

---

### C3 · P0 — Transport: one primary cluster + one labelled chip row

**Problem:** five icon buttons of identical weight in one row (timer, −15, play, +15, **bare `+`**), plus a lone outlined "Ver cola" pill hanging below. A 5-year-old cannot decode a naked `+`, and the equal weights destroy the hierarchy.

**Delta:** split into two rows.

**Row A — `@id/player_transport` (primary, centred):** only three controls.

```
[ −15 ]      ( ▶ )      [ +15 ]
  60dp        88dp        60dp
        gap 20dp    gap 20dp
```

- `player_play_size`: **88dp** (was 72/64). `bg_play_circle` unchanged (mint oval). Icon `player_icon_play`: **40dp**, `#17152E`.
- Skip buttons: touch target stays `52dp` min but set width/height **60dp**; icon `player_transport_icon` **32dp** (was 52 dp — the glyphs are currently as tall as their button, which is why they read as blobs). Tint `#F8F7FF`.
- `player_transport_gap`: **20dp** (was 8dp).
- Remove `player_timer_btn` and `player_queue_btn` from this row.

**Row B — `@id/player_chips` (new `LinearLayout`, horizontal, `gravity="center"`, `layout_marginTop="16dp"`, child gap 8 dp):** three labelled chips, each `minHeight="52dp"`, `paddingStart/End="16dp"`, icon 24 dp + 8 dp gap + label Nunito Bold 14 sp.

| id | icon | label (ES) | background | text/icon |
|---|---|---|---|---|
| `player_timer_btn` | `ic_timer` | `Temporizador` | `bg_chip_control` | `#FFB66E` |
| `player_queue_btn` | `ic_add` | `Añadir` | `bg_chip_control` | `#F8F7FF` |
| `player_ver_cola` | `ic_overflow`→ list glyph | `Ver cola` | `bg_chip_control` | `#72E0B8` |

New drawable `bg_chip_control.xml`: `<solid #332E5C/>` + `<corners 16dp/>`. No stroke — the chips read against the `#28244B` panel by value. Drop `bg_action_outline` here (the mint outline pill is the thing that reads "floating" in the screenshot).

When the timer is **active**, swap `player_timer_btn` background to a peach-filled `bg_chip_timer_on.xml` (`<solid #FFB66E/>`, `<corners 16dp/>`) with `#17152E` bold label — same trick already used by `bg_timer_row_selected`.

New strings: `Temporizador`, `Añadir`, `Ver cola` (last two already exist as `anadir_a_la_cola` / `ver_cola` — shorten the chip labels, keep the long form as `contentDescription`).

---

### C4 · P0 — Seek bar: a thin line, not a slab

**Problem (owner issue 4):** the mint progress renders as a full-height rectangle. Root cause was the missing `<clip>` on the progress layer — already fixed in the working tree. This change locks the rest of the spec so it stays thin.

`drawable/bg_seekbar_track.xml`:
- background layer: `android:height="6dp"`, `gravity="center_vertical"`, `#66AAA3CE`, corners `3dp`. *(unchanged)*
- progress layer: `android:height="6dp"`, `gravity="center_vertical"`, **wrapped in `<clip>`**, `#72E0B8`, corners `3dp`. *(the in-flight fix — keep it)*
- Add a third layer `android:id="@android:id/secondaryProgress"` — **not needed**, omit; there is no buffering concept in a bundled-asset app.

`drawable/bg_seek_thumb.xml`: oval `#F8F7FF`, size **20dp × 20dp** (was 16). At 6 dp track / 20 dp thumb the ratio reads as a modern player, not a scrollbar.

`@id/player_seek` in both layouts:
- `layout_height="@dimen/min_touch"` (52 dp), `splitTrack="false"`, `progressDrawable`/`thumb` as above. *(unchanged)*
- `android:paddingStart="0dp" android:paddingEnd="0dp"` and **remove** the `paddingStart/End="16dp"` from the wrapping `@id/player_seek_block` — inside the panel, the 28 dp panel padding is the only inset the bar needs, and the double inset is what makes the bar look short and centred-in-nothing today.

**Time labels:** move `player_position` / `player_duration` from **above** the bar to **below** it, `layout_marginTop="6dp"`, 12 sp Nunito `#AAA3CE`, position start-aligned, duration end-aligned. Reading order becomes bar → time, which is the convention every player the parent has ever used follows.

---

### C5 · P1 — Catalog card: framed cover, no dead band, aligned rows

**Problem:** cover is edge-to-edge with square top corners against a 16 dp rounded card; the `⋮` button sits alone under the synopsis creating a 52 dp empty band at the bottom of every card (very visible in the screenshot); `adjustViewBounds` + `wrap_content` means every cover has a different height so the grid rows never align.

**Delta:** `layout/item_story_card.xml`, rebuilt as a framed tile.

Root `ConstraintLayout`:
- `android:layout_margin="8dp"` *(keep — with C6 this yields uniform 16 dp gutters)*
- `android:background="@drawable/bg_card"`, `android:padding="8dp"`
- `bg_card` corners: `corner_card` **20dp** (was 16).

`@id/story_cover` (`ImageView`):
- `layout_width="0dp"`, `layout_height="0dp"`, `app:layout_constraintDimensionRatio="H,16:9"` — **fixed aspect, so every card is exactly the same height and the grid rows line up.**
- `android:scaleType="fitCenter"`, **remove** `adjustViewBounds` and `wrap_content`.
- `android:background="@drawable/bg_cover_matte"` (new: `<solid @color/matte/>` + `<corners 12dp/>`).
- In `StoryAdapter.StoryHolder.init`: `cover.clipToOutline = true` — one line, API 21+, gives real rounded corners on the bitmap without Material or custom drawing.
- Constrained to parent start/top/end (inside the 8 dp root padding).

`@id/story_title`: Fredoka 16 sp `#F8F7FF`, `android:lines="2"` (**not** `maxLines` — fixed 2 lines keeps every card identical), `ellipsize="end"`, `marginTop="10dp"`, `marginStart/End="4dp"`.

`@id/story_synopsis`: Nunito Regular **13 sp** `#AAA3CE`, `android:lines="2"`, `ellipsize="end"`, `marginTop="4dp"`, `marginStart/End="4dp"`, `lineSpacingExtra="1dp"`. Constrain `bottom_toBottomOf="parent"` so the card closes right after the text — **the dead band disappears.**

`@id/overflow_btn`: **move onto the cover, top-right.**
- `layout_width/height="@dimen/min_touch"` (52 dp, touch preserved)
- constrained `top_toTopOf="@id/story_cover"` / `end_toEndOf="@id/story_cover"`, `layout_margin="2dp"`
- background: new `bg_overflow_circle.xml` — `<shape android:shape="oval"><solid @color/scrim_overflow/></shape>`, inner icon 20 dp `#F8F7FF`.

`@id/chip_duration` stays bottom-end of the cover, `@id/pill_sonando` bottom-start of the cover, both `layout_margin="6dp"` (was 8), `corner_chip`/`corner_pill` **8dp**.

---

### C6 · P1 — Catalog grid rhythm + header

**Problem (owner issue 2):** gutters are inconsistent (12 dp grid padding + 8 dp item margin → 20 dp at the edges vs 16 dp between cards), and 5 columns on a 10" tablet makes each cover ~220 dp wide — small art for a 5-year-old.

**Delta:**

1. `values-w1024dp/integers.xml`: `catalog_grid_columns` **4** (was 5). Cell ≈ 316 dp → cover ≈ 284 × 160 dp. Bigger, more tappable, more "shelf of picture books". Portrait (`w768dp`) stays **3**; small stays **2**.
2. `values/dimens.xml`: `grid_padding_h` **8dp** (was 12), `grid_padding_top` **8dp**. With the card's own 8 dp margin this produces a **uniform 16 dp gutter everywhere** — edges and interior alike.
3. `grid_padding_bottom` **40dp** (was 32) / `grid_padding_bottom_mini` **12dp** (was 16) — the miniplayer is *in-flow*, so it already takes its own space plus an 8 dp margin (C8); 12 dp is just the breathing gap between the last row and the bar.
4. Header (`fragment_catalog.xml`): `header_padding_h` **20dp** *(unchanged, aligns with the 8+8 gutter within 4 dp — acceptable)*, `header_padding_top` **24dp**, `header_padding_bottom` **16dp`. Title `TextAppearance.Jc.30` Fredoka `#F8F7FF`; subtitle 14 sp `#AAA3CE`, `marginTop="4dp"`.

---

### C7 · P1 — Player portrait stack

Same system, single column. `layout/fragment_player.xml`:

- Top bar unchanged (✕ + eyebrow `REPRODUCIENDO` 12 sp `#AAA3CE` + spacer), but the ✕ icon drops to 28 dp inside its 56 dp target, matching C1.
- `@id/player_content`: `layout_width="match_parent"`, `layout_marginStart/End="24dp"`, `background="@drawable/bg_panel"`, `padding="24dp"`.
- Cover **outside** the panel, above it: `player_cover_w="0dp"` → in portrait use `layout_width="match_parent"`, `layout_marginStart/End="24dp"`, `layout_constraintDimensionRatio` handled by fixed dimens: **`player_cover_w=704dp` / `player_cover_h=396dp`** in `values-port` (or simply width `match_parent` with the height set to `396dp` on an 800 dp-wide screen).
- Gap cover → panel: **24dp**.
- Inside the panel, identical ladder to C1: title 28 sp / desc +8 / seek +24 / transport +24 / chips +16 / timer line +16.
- Portrait `player_desc_max_lines` stays **2**.
- Height budget: 56 (top bar) + 396 (cover) + 24 + ~450 (panel) + 32 (bottom) ≈ 958 dp on a 1280 dp-tall portrait screen. Still **no scrolling**, as specified.

---

### C8 · P2 — Miniplayer bar: floating card, not a slab

`layout/view_mini_player.xml` + `bg_miniplayer.xml`:

- `bg_miniplayer`: corners **20dp on all four**, `<solid @color/superficie/>`, `<stroke 1dp @color/borde/>`. Root gets `layout_marginStart/End/Bottom="8dp"` — the bar reads as a card that belongs to the same family as the story cards, instead of a UI chrome slab.
- `mini_bar_height` **72dp** (was 64). `mini_inset_bottom` **8dp** *(unchanged)*.
- `mini_strip_height` **4dp** (was 6), full-bleed at the top of the card, mint `#72E0B8` over `#3D3860`.
- `mini_cover` **56dp** (was 48), `background="@drawable/bg_cover_matte"`, `scaleType="fitCenter"`, `clipToOutline = true` in `MiniPlayerView` — same whole-cover rule as everywhere else.
- `mini_padding_h` **16dp** (was 10).
- `mini_title`: **Fredoka 16 sp** `#F8F7FF` (was Nunito) — the title is display type in this app. `mini_status`: 12 sp `#AAA3CE` (`Sonando` / `En pausa`), `mini_text_gap="2dp"`.
- `mini_play_btn`: 52 dp, `bg_play_chip` corners **16dp** (was 12), icon 26 dp `#17152E`. `mini_next_btn`: 52 dp, icon **26dp** `#F8F7FF`, `layout_marginStart="4dp"`.

---

### C9 · P2 — Queue rows

`layout/item_queue_row.xml` + `bg_row_surface.xml`:

- `bg_row_surface` corners **20dp** (was 16). *(Shared with timer rows — intended.)*
- `queue_gap` (row spacing) **8dp** (was 4).
- `queue_row_min_height` **76dp** (was 72); `paddingStart="12dp"`, `paddingEnd="8dp"`, `paddingTop/Bottom="10dp"`.
- `queue_cover` **60dp** (was 56), `corner_cover` **12dp** (was 10), `background="@drawable/bg_cover_matte"`, `scaleType="fitCenter"`, `clipToOutline = true` in the adapter holder.
- Title: **Fredoka 16 sp** `#F8F7FF`, `maxLines="2"`, `marginStart="12dp"`. Add a second line below it: duration, Nunito 12 sp `#AAA3CE`, `marginTop="2dp"` (reuse `TimeFormat.formatTime`).
- Buttons: keep 52 dp touch targets, drop the inner icon from `queue_btn` 48 dp → **26dp** (they are currently near-full-bleed inside their targets, which is why the chevrons look heavy). Chevrons `#AAA3CE`, remove `#FFB66E`.
- Ordering affordance: see C12.

---

### C10 · P2 — Timer option rows

`layout/fragment_timer.xml`:

- `timer_row_height` **64dp** (was 56), `timer_list_gap` **10dp** (was 8).
- `bg_row_surface` (20 dp corners, `#28244B`) for unselected; `bg_timer_row_selected` → corners **20dp**, `<solid #FFB66E/>` for selected.
- Text alignment: `gravity="center_vertical|start"` + `paddingStart="20dp"` (was `center`). Left-aligned option lists are faster to scan than centred ones; centring is what makes the current list read as five identical buttons.
- Unselected: Nunito **Bold 16 sp** `#F8F7FF`. Selected: Nunito Bold 16 sp `#17152E` + trailing `ic_check_mint` retinted to `#17152E` at 24 dp, `paddingEnd="20dp"` (`drawableEnd` + `drawableTint`; API 22 → use `TextViewCompat.setCompoundDrawableTintList` or a pre-tinted `ic_check_dark.xml`).
- Helper copy `timer_helper`: 14 sp `#AAA3CE`, `paddingBottom="16dp"`.
- List container `paddingStart/End`: **20dp**, matching the header.

---

### C11 · Delight — "Marco Sonando": the playing cover gets a mint frame

Today "currently playing" is a small mint pill in the cover corner. Make the **whole card** say it — the single strongest kid-legible state in the app.

- New drawable `bg_card_sonando.xml`: `<solid @color/superficie/>` + `<stroke android:width="4dp" android:color="@color/accion"/>` + `<corners 20dp/>`.
- In `StoryAdapter.bindPill()`: when `isCurrent`, `holder.itemView.setBackgroundResource(R.drawable.bg_card_sonando)`, else `R.drawable.bg_card`. Keep the `Sonando` pill (it names the state for the adult).
- No animation, no glow — a 4 dp mint edge on a dark ground is loud enough, and it costs one drawable swap on an existing `notifyItemChanged(PAYLOAD_CURRENT)` path that already exists.
- Same idea on the player: when the sleep timer is on, `CoverHaloView` already draws its 3 dp peach outer ring — bump it to **4dp** so the two "framed" states read at the same weight.

---

### C12 · Delight — Queue order numbers

A 5-year-old can't read "the order of the list" from vertical position alone, but she can count.

- Add to `item_queue_row.xml`, first child, before the cover: a 32 dp `TextView` `@+id/queue_index`, `background="@drawable/bg_index_circle"` (new: `<shape oval><solid @color/accion/></shape>`), `gravity="center"`, Fredoka **18 sp**, `textColor="#17152E"`, `layout_marginEnd="12dp"`.
- Bind `(position + 1).toString()` in the queue adapter; the existing reorder callbacks already trigger a rebind.
- The currently-playing item gets `#FFB66E` (peach) instead of mint — "this one is on now, these are next".

*(Third delight touch is already folded into C3: the labelled Spanish chips `Temporizador` / `Añadir` / `Ver cola` replace naked icons a child cannot decode. It is the cheapest usability-as-delight win in the set.)*

---

## Token summary (copy-paste checklist)

**colors.xml (+3):** `superficieAlta #332E5C`, `matte #201C3D`, `borde #3D3860`.

**New drawables (+8):** `bg_panel`, `bg_circle_surface`, `bg_chip_control`, `bg_chip_timer_on`, `bg_cover_matte`, `bg_overflow_circle`, `bg_card_sonando`, `bg_index_circle`.

**dimens.xml changed:**

```
corner_card            16 -> 20
corner_mini            12 -> 20
corner_chip             8 -> 8    (unchanged)
corner_panel           new 24
grid_padding_h         12 -> 8
grid_padding_top        8 -> 8    (unchanged)
grid_padding_bottom    32 -> 40
grid_padding_bottom_mini 16 -> 12
card_title_size        16 -> 16   (unchanged)
mini_bar_height        64 -> 72
mini_cover             48 -> 56
mini_strip_height       6 -> 4
mini_padding_h         10 -> 16
mini_icon              24 -> 26
player_cover        340sq -> 560x315 land / 704x396 port
player_land_gap        28 -> 32
player_controls_width 520 -> 560 (renamed player_panel_w)
player_panel_pad      new 28 (land) / 24 (port)
player_play_size    72/64 -> 88
player_icon_play       34 -> 40
player_transport_icon  52 -> 32
player_transport_gap    8 -> 20
player_desc_gap         8 -> 8    (unchanged)
player_seek_labels_gap  8 -> 6    (now below the bar)
player_transport_top_gap 24 -> 24 (unchanged)
player_pill_top_gap    24 -> 16   (now the chip row gap)
queue_row_min_height   72 -> 76
queue_cover            56 -> 60
queue_btn              48 -> 26   (icon size; touch stays 52)
queue_gap               4 -> 8
corner_cover           10 -> 12
timer_row_height       56 -> 64
timer_list_gap          8 -> 10
```

**integers.xml:** `catalog_grid_columns` (w1024dp) `5 -> 4`.

**styles.xml (+1 rung):** `TextAppearance.Jc.28` — Fredoka 28 sp `#F8F7FF` (player hero title).

**Kotlin touches (4 one-liners):** `cover.clipToOutline = true` in `StoryAdapter`, `MiniPlayerView`, the queue adapter holder; `CoverHaloView` rect-aware `onDraw` + `FIT_CENTER` + matte background; `StoryAdapter.bindPill` background swap; queue index bind.

---

## Rationale — the visual system in one paragraph

The app has exactly one structural idea and it should be used everywhere: **art in a frame, on a mat, inside a flat card.** Every cover in Julicuentos is 16:9 source art, so every place a cover appears — catalog tile, player hero, miniplayer, queue row — it gets the same treatment: a fixed 16:9 box, `fitCenter` so the whole picture survives, a `#201C3D` matte behind it so the letterbox reads as a passe-partout rather than a gap, and a 12–20 dp rounded clip. On top of that sits a two-step surface ladder — `#17152E` ground, `#28244B` card/panel, `#332E5C` control chip — which is how this app gets depth on hardware that cannot afford a single shadow: **value, not elevation.** The player's whole problem today is that its right column has no card, so the ladder has only one rung and everything floats; giving it a `#28244B` panel with 28 dp padding, a strict 8/16/24 vertical ladder and two equal 560 dp columns turns a pile of widgets into a designed spread in one edit. Colour then carries meaning and nothing else: mint `#72E0B8` is always "playing / go" (play circle, progress, the 4 dp frame on the playing card, queue numbers), peach `#FFB66E` is always "timer" (chip when armed, cover ring, current-item number), `#F8F7FF` is what you read and `#AAA3CE` is what you skim. Type follows the same discipline — Fredoka is identity (titles at 30/28/20/16, queue numbers), Nunito is information (16/14/13/12) — and touch is never below 52 dp even when the glyph inside shrinks to 26–32 dp, because the current icons look heavy precisely for filling their own hit boxes. The result should read like a shelf of picture books with one big one open on the table: modern, calm, unmistakably for a child, and drawable entirely with `<shape>`, `clipToOutline` and one existing `onDraw`.
