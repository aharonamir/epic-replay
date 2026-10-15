# Next session: finish T003 with contact sheets (spec revision 7)

Written 2026-10-15 for the Claude Code session on the owner's machine, after `epic-replay-r7.patch` is applied. Paste the prompt below. Use Opus for this session; Sonnet is fine for the build days that follow.

## What revision 7 changed, in one paragraph

The owner agreed on 2026-10-15: nine working days (Friday 23 October is not a working day); the key moment is found by the signal scan (S1, built now) plus contact sheets that the model answers by cell, refined in passes and snapped to a sound onset; the Spotter runs as a fixed sequence; key lines are placed by their key word (`hit_word`); two narrators; the cuts listed in `tasks.md`. Read spec.md sections 8 and 10, plan.md sections 4.2 to 4.4, 4.8 and 6, and tasks.md days 4 to 9.

New in the code: `tools/signal_scan.py`, `tools/contact_sheet.py`, `tools/make_test_clip.py`, `tools/spike_t003_sheets.py`, `VisionAdapter.look_sheet` with a replay test, settings `EPIC_SHEET_PASSES` and `EPIC_SOUND_SNAP_S`, dependencies `opencv-python-headless` and `scipy`, CLAUDE.md rule 14.

## Prompt

```
Read CLAUDE.md, docs/next-session.md, docs/handoff.md, and in specs/001-epic-replay/:
spec.md sections 8 and 10, plan.md sections 4.2 to 4.4, 4.8 and 6, and tasks.md
from "Day 4" on. Spec revision 7 is agreed by the owner; do not reopen it. Today is
day 4, afternoon (Thursday 15 October). The task is T003 with contact sheets.

1. Install the new dependencies (uv pip install -e ".[dev]"). Run pytest and
   ruff check . && ruff format --check . and tell me the result.
2. Plumbing check, no cost: python tools/spike_t003_sheets.py --synthetic --fake.
   Expected: errors of about -1 frame on both repeats, agreed True. Delete the
   *_fake.json and *_fake.md it writes in eval/spike/.
3. Signal scan on every starter clip, no cost: python tools/signal_scan.py on each
   clip in eval/clips/. Give me one table: clip, label time(s), the three candidate
   times with their sound onsets, cuts found, and whether a labelled moment lies
   within 1.5 s of a candidate. Do not look at frames or sheets yourself (rule 5 of
   the handoff: only the vision endpoint and I see them).
4. Labels: for each clip whose climax_t is a whole second, make one sheet around it
   with tools/contact_sheet.py (from climax_t - 1.5 to climax_t + 1.5) and a second
   pass when I name a cell. I open the sheets and tell you the cell; you write the
   frame-exact time into eval/labels/<clip>.json and commit "T006: frame-exact labels".
5. python tools/spike_t003_sheets.py --dry-run. Tell me the number of calls and the
   cost upper bound, and wait for my yes.
6. After my yes: EPIC_PROVIDER_MODE=record python tools/spike_t003_sheets.py --yes,
   in the background, reporting each clip as it finishes. If a call fails, stop and
   tell me; do not loop.
7. Write a "T003 contact sheets" section at the end of docs/provider-notes.md: the
   table from eval/spike/t003_sheets.md; the share of clips within
   EPIC_KEYFRAME_TOL_S, the share where the repeats agree, the median absolute error
   in frames, how often the sound snap was used, latency and cost per clip. Tell me
   to open eval/spike/frames/sheets/t003_sheets_report.html and mark the outcome
   right or wrong per clip; write my answers into the table.
8. Gate 0 (plan.md section 6): answer each line yes or no from the table. Re-set the
   SC-2 key-moment target in spec.md section 5 from the measured share, and SC-1 and
   SC-5 from the measured latency and cost. If the key-moment line is no, stop and
   tell me; plan.md section 6 says what happens then.
9. Tick T003 in tasks.md, commit "T003: contact sheets; Gate 0", push, and stop.
   Report what works, what does not, and the numbers.
```

## The days after

Each later session uses the short prompt in `START-PROMPT.md`: "Read CLAUDE.md and specs/001-epic-replay/tasks.md. Continue with the next unchecked task that is not deferred. Stop at the next gate and report against its exit criteria in plan.md section 6."

T011 and T012 move the two tools into `src/epic_replay/` (stages and media) and build the fixed sequence from `tools/spike_t003_sheets.py`; keep its prompts and constants unless the T003 numbers argue otherwise.
