# Visual design of the page

Revision 5 (2026-10-11). The owner approved this design on the design canvas "Epic Replay page". [ui.md](ui.md) says what is on the page and what it does; this file says what it looks like. Where the two disagree about wording or behaviour, ui.md wins. Where they disagree about appearance, this file wins.

Sources:

- The canvas: https://claude.ai/artifact/W4itfPg6U9SxjM5xM2UVRW (private to the owner).
- A copy of the canvas files is in [design-canvas/](design-canvas/README.md). Every measurement below was taken from them. Lift values from them, not structure: they are written with inline styles for the canvas editor, and the product uses one stylesheet.

The canvas shows the four states at 1280 px and at 390 px. The Stopped state, the "Fix something" and "Not at all" views, the short progress list of a re-telling and the demo band are not drawn; section 7 says how to build them from the same parts.

## 1. Direction

A dark broadcast look: a near-black ground, heavy condensed capitals for headlines, a monospace face for anything that is a time, a frame number or a file. The page itself has no colour of its own. Its one accent is the colour of the chosen narrator, taken from the style pack, so choosing a narrator recolours the page and every later state carries the colour of the narrator who is telling the play.

Two motifs come from the subject and are used sparingly:

- **The frame ruler**: a 9 px strip of ticks under the header.
- **The viewfinder**: four corner marks on anything that stands for the user's clip.

## 2. Colour

| Token | Value | Used for | Contrast |
|-------|-------|----------|----------|
| `--ground` | `#0D1012` | Page background | |
| `--surface` | `#161A1D` | Drop area, unselected narrator card, play card | |
| `--raised` | `#1E2327` | Selected card, highlighted play-card item, disabled button | |
| `--line` | `#2C3338` | Dividers, card borders, empty bar segments, ruler ticks | |
| `--line-strong` | `#414A51` | Border of a disabled button | |
| `--control-line` | `#5A646B` | Border of secondary buttons and the text field; viewfinder marks | 3.2:1 on ground |
| `--text` | `#F2F4F3` | Text | 17.3:1 on ground, 14.3:1 on raised |
| `--text-soft` | `#D9DEE0` | Long read-only text inside panels | 14.1:1 on ground |
| `--muted` | `#AAB3B9` | Secondary text, durations, waiting steps | 9.0:1 on ground, 7.4:1 on raised |
| `--waiting` | `#7A858D` | The ring of a waiting step (not text) | 5.1:1 on ground |
| `--ink` | `#121416` | Text on a light fill | 16.7:1 on `--text` |
| `--danger-text` | `#FF7C8A` | "Delete this clip now" | 7.7:1 on ground |
| `--placeholder-a`, `--placeholder-b` | `#1E2327`, `#232A2F` | 10 px diagonal stripes where a frame has not loaded | |

The narrator accent is three values, set on the page root as custom properties when the style is known:

| Property | Source | Football commentator | News reporter | Nature documentary |
|----------|--------|----------------------|---------------|--------------------|
| `--accent` | pack `graphics.accent` | `#FFD23F` | `#D7263D` | `#7FB069` |
| `--on-accent` | computed: `--ink` or `#FFFFFF`, whichever has the higher contrast on `--accent` | `#121416` (12.8:1) | `#FFFFFF` (5.0:1) | `#121416` (7.3:1) |
| `--accent-text` | pack `graphics.accent_text`, or `graphics.accent` when absent | `#FFD23F` (13.2:1) | `#FF7C8A` (7.7:1) | `#9CCB86` (10.3:1) |

- `--accent` is for fills and borders: the primary button, the selected card's border, filled bar segments, the running-step mark, the logo mark.
- `--accent-text` is for small coloured text on the dark ground: the second line of the headline, "Working", "Check this", the dashed border of a highlighted item. It exists because a pack's accent can be too dark to read as text: the News reporter's red is 3.8:1 on the ground.
- The loader (T038) rejects a pack whose `--accent-text` is below 4.5:1 on `--raised`, or whose `--on-accent` is below 4.5:1 on `--accent`.
- No code names a style. The page reads the three values from `GET /styles`.

No gradients except the placeholder stripes and the ruler. No shadows.

## 3. Type

| Role | Family | Fallback | Weights |
|------|--------|----------|---------|
| Display | Sofia Sans Extra Condensed | `'Arial Narrow', sans-serif` | 700, 900, 900 italic |
| Text | Sofia Sans | `system-ui, sans-serif` | 400, 500, 600, 700, 400 italic |
| Data | JetBrains Mono | `ui-monospace, monospace` | 400, 500 |

All three are under the SIL Open Font License. Keep the font files in `assets/fonts/`, with their licence lines in `assets/LICENCES.md`, and serve them from the app so the page makes no outside request and works at a venue without a network.

| Use | Family | Size | Weight | Other |
|-----|--------|------|--------|-------|
| Start headline | Display | `clamp(64px, 8vw, 104px)` | 900 | line-height 0.9, uppercase, tracking 0.01em |
| Result headline | Display | `clamp(46px, 6vw, 64px)` | 900 | line-height 0.95, uppercase |
| Status line ("About a minute to go.") | Display | 46 px | 900 | line-height 0.95, uppercase |
| Play card activity | Display | 38 px | 900 | uppercase, tracking 0.02em |
| Section heading ("Who's narrating?") | Display | 30 px | 700 | uppercase, tracking 0.03em |
| Wordmark | Display | 30 px | 900 | uppercase, tracking 0.04em |
| Primary button | Display | 28 to 30 px | 900 | uppercase, tracking 0.04em |
| Card title, open step title | Text | 19 to 21 px | 700 | line-height 1.2 |
| Body | Text | 17 px | 400 | line-height 1.45 |
| Secondary text | Text | 15 px | 400 | `--muted` |
| Small label ("Optional", "Working", "Check this", "AI commentary") | Text | 12 to 13 px | 700 | uppercase, tracking 0.1em |
| Times, frame numbers, file names, stage details | Data | 13 to 15 px | 400 | |

Copy is written in sentence case and set in capitals by the stylesheet.

## 4. Layout

The layout has no breakpoints. Every two-column area is a wrapping flex row whose children have a preferred width, so a phone gets one column and a laptop two, from the same markup.

- Page: full height, a column. Header, ruler, then `main` with `max-width: 1280px`, centred, padding `clamp(24px, 4vw, 44px)` top, `clamp(16px, 4vw, 48px)` sides, 48 px bottom.
- Header: wordmark with the two-triangle rewind mark (30 by 20 px, `--accent`) on the left; on Progress and Confirm, the narrator's dot, name and elapsed time on the right. Padding 16 px top and bottom. It wraps on a narrow screen.
- Column gaps: 56 px across, 32 to 40 px down.

| State | Left column | Right column | Below |
|-------|-------------|--------------|-------|
| Start | `flex: 1 1 420px`: headline, drop area (grows to the column's height, at least 260 px), tip | `flex: 1 1 420px`: narrator cards, optional fields | A full-width row above a divider: consent and the provider line (`flex: 999 1 420px`), then the button (`flex: 1 1 240px`, 64 px high) |
| Progress | `flex: 1 1 300px`, at most 440 px: clip thumbnail (16:9), status line | `flex: 2 1 480px`: the eight steps, then "Show details" at the right | Above both: label, "Step 5 of 8", the segmented bar |
| Confirm | `flex: 1 1 260px`, at most 340 px: clip thumbnail, "Your turn." | `flex: 3 1 520px`: the steps, with step 4 open | Same bar |
| Result | `flex: 3 1 560px`: headline, version tabs, player (16:9), download row, the three panels | `flex: 1 1 300px`: "Try another narrator", then start over, keep and delete above a divider | |

Order on a phone is the order of the table read left to right, top to bottom: on Start, headline, drop area, narrators, optional fields, consent, button.

## 5. Parts

**Buttons.** All are at least 44 px high with a 4 px radius.

| Kind | Look | Used for |
|------|------|----------|
| Primary | `--accent` fill, `--on-accent` text, display face, 56 to 64 px high | "Make my replay", "Yes, that's it", "Download" |
| Primary, disabled | `--raised` fill, `--muted` text, 1 px `--line-strong` border | The same buttons before their conditions are met |
| Secondary, strong | Transparent, 1.5 px `--text` border, weight 600 | "Choose a file", "Start over with a new clip" |
| Secondary | Transparent, 1 px `--control-line` border, weight 600 | "Listen", "Change", "Earlier", "Later", "Fix something", "Not at all", "Show details", "Write it again" |
| Choice | Secondary; when chosen, `--accent` fill and `--on-accent` text | "Keep", "Remove" |
| Text | No border, underlined, `--danger-text` | "Delete this clip now" |

**Drop area.** `--surface`, no border, viewfinder marks 10 px in from each corner (24 px arms, 2 px, `--control-line`). Empty: a film-frame icon, the two lines of copy, "Choose a file", the limits in the data face. With a file: the clip's first frame fills the area, with the file name and length in the data face and "Change" beneath.

**Narrator card.** A radio button, the name, the description, a sample caption, and "Listen" at the right (below, on a phone). 2 px border, 6 px radius, padding 14 by 16 px, 12 px between cards. Unselected: `--surface` with a `--line` border. Selected: `--raised` with an `--accent` border. The radio button takes the card's own pack accent.

The sample caption shows the pack's `example_lines.climax` in the pack's own lower-third layout, so the card previews the graphics as well as the voice:

| `lower_third_layout` | Sample caption |
|----------------------|----------------|
| `strap` | The line in display 900 italic capitals, 20 px, `--ink` on the pack accent |
| `ticker` | The pack's `end_card_kicker` in 12 px capitals, white on the pack accent, joined to the line in 14 px weight 600, `--ink` on `--text` |
| `caption` | The line in 16 px italic, `#D9E5D2`, under a 1 px rule in the pack accent |

**Segmented bar.** Eight segments, 12 px high, 4 px apart. Finished: `--accent` fill. Running or waiting for the user: a 2 px `--accent` outline. Not started: `--line` fill. "Step N of 8" in the data face sits above it at the right. The bar changes only when a step finishes.

**Step row.** At least 56 px high, a 1 px `--line` rule between rows: a 24 px mark, the step name, and at the right the duration in the data face or the word "Working".

| State | Mark | Name | Right |
|-------|------|------|-------|
| Waiting | Ring, `--waiting` | `--muted` | nothing |
| Running | Ring with a filled centre, `--accent` | weight 700 | "Working", small label, `--accent-text` |
| Done | Ring with a tick, `--text` | `--text` | duration, `--muted` |
| Done with a note | As done, with the note beneath the name in 15 px `--muted` | | |

With details open, one line in the data face (13 px, `--muted`) appears under each name: stage id, who ran it, and what it did.

**Play card.** `--surface`, 1 px `--line` border, 6 px radius, padding `clamp(16px, 3vw, 24px)`. Inside, a wrapping row: the key-moment frame (`flex: 1 1 250px`, at most 380 px) with "Frame 313 · 10.43 s" in the data face and the nudge buttons beneath (one frame and ten frames each way), then (revision 8) "Other moments": a wrapping row of frames 72 px wide, each with its time in the data face, the chosen one with a 2 px `--accent-text` border; the key-moment frame is styled as an item to check until it is answered; and the facts (`flex: 2 1 300px`): the activity as a heading, then the numbered events with their numbers in the data face. The outcome carries a tag: 12 px capitals, `--ink` on `--text`. An item to check has a `--raised` fill, a 1 px dashed `--accent-text` border, the label "Check this" and its two choice buttons. Under a rule: the three action buttons in a wrapping row (`flex: 2 1 200px` for the primary, `1 1 150px` for the others, 56 px high), then the cost line.

**Version tabs.** Buttons, 44 px high, each with a 10 px dot in its own pack accent. Current: `--raised` with a 2 px border in its accent, weight 700. Others: transparent with a 2 px `--line` border. A version still being made carries the word "making" in the data face.

**Player.** 16:9. Its poster is the first frame of the video, which is the title card. A round 88 px play button, `--text` fill with an `--ink` triangle, sits in the centre. The video does not start by itself.

**Panels.** Three native disclosure elements under the player, separated by `--line` rules, summary in weight 700. "The script" is open when the result first appears; the other two are closed. A script line is a wrapping row: time and role in the data face (168 px wide, `--muted`, or the narrator's accent text for the two key lines), then the words. The climax line is weight 700.

**Form controls.** The text field is 48 px high, `--ground` fill, 1 px `--control-line` border, 4 px radius. Checkboxes and radio buttons are 20 px and use `accent-color`. Every label row is at least 44 px high.

**Frames that have not loaded.** Diagonal stripes of the two placeholder colours with a short caption in the data face. On the canvas every picture is such a placeholder, because there was no footage.

## 6. Behaviour that affects appearance

- Choosing a narrator on Start sets the three accent properties at once, with no transition.
- "Make my replay" is disabled until a valid clip is chosen and consent is ticked. "Yes, that's it" is disabled until every item marked "Check this" has an answer.
- The running mark may pulse (opacity 1 to 0.5, 1.2 s) and stays still under `prefers-reduced-motion`. Nothing else moves.
- Focus: a 2 px `--accent-text` outline, 2 px away from the control. The canvas does not draw it; the page must.

## 7. States the canvas does not draw

| State | Build it from |
|-------|---------------|
| Stopped | The Progress layout. The failed step's mark becomes a ring with an exclamation mark in `--danger-text`; the status line gives the message from ui.md; one primary button gives the action |
| "Fix something" | The play card, with the activity and the outcome sentence as text fields. On the card the fields take a 1 px `--muted` border, because `--control-line` is too faint there |
| "Not at all" | The play card's facts replaced by a text field for one line (bordered as above) and two buttons: a primary "Try again" and a secondary for a narration with no specific claims |
| Re-telling | On Result, under the tabs, step rows 5 to 8 only, without the bar. The current video stays in the player |
| Demo band | A full-width strip above the header: `--raised`, small label style, "Cached run from <date>" |

## 8. Accessibility

- Text is at least 4.5:1 on what is behind it; section 2 gives the measured pairs.
- Colour is never the only signal: step states differ in mark and wording, the item to check carries the words "Check this", the chosen narrator has a ticked radio button, a version being made says "making".
- Controls are real buttons, links, inputs and labels, reachable by Tab, at least 44 px high. Icon-only buttons have a text name.
- Secondary buttons are identified by their text; their border is 3.2:1 on the ground and 2.9:1 on the play card, which is enough for a decoration and is why the text field, which has no label inside it, sits on the ground and not on a card.

## 9. If the page is running over its hours

Cut in this order; none of it changes what the page does: the sample captions on the narrator cards (show name and description only), the viewfinder marks, the frame ruler, the pulse on the running mark, the display face on the status line.

## 10. Build notes

- One stylesheet, `web/static/page.css`, with the tokens of section 2 as custom properties on `:root`. No build step.
- The template writes the three accent properties on the root element from the run's style; on Start, the script that handles the radio group rewrites them from the data `GET /styles` returned.
- Sizes that use `vw` are the two headlines and the page padding. Nothing uses a media query.
