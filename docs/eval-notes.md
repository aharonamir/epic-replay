# Evaluation notes

## T034 rehearsal, 2026-10-30

Three live runs in a row at commit `880a73c`, no code touched between them, on single-shot clips, one style each. The total was 0.1617 USD against the 0.20 USD stated beforehand. All three finished with status `done`, HTML graphics and the S6 checks passed. The run folders are `runs/rehearsal_1` to `runs/rehearsal_3`.

| Run | Clip | Length | Style | Pick (from the owner's label) |
|---|---|---|---|---|
| 1 | FlippingPancakes | 16.7 s | Football commentator | frame 60, 2.0 s |
| 2 | Skateboard_trick | 20.2 s | News reporter | frame 180, 6.0 s |
| 3 | basket_ball_shot | 18.1 s | Nature documentary | frame 87, 2.9 s |

### SC-1 Speed

Target: for a 20 second clip, at most 60 s from upload to play card and 90 s from confirmed card to video, at the median of 10 runs.

| Run | S0 to S3 (card ready) | S4 to S10 (video ready) |
|---|---|---|
| 1 | 27.1 s | 16.7 s |
| 2 | 33.9 s | 15.8 s |
| 3 | 49.1 s | 20.2 s |
| Median of 3 | 33.9 s | 16.7 s |

All three are inside both limits. S2 is most of the first figure (25.1, 31.8 and 46.9 s). The T003 slowest call was 71 s, so a slow provider can still take a run past 60 s.

A further style of the same play: 34.7 s and 18.6 s, from the re-tellings of 2026-10-30 (T034 first part), not from these runs. The target is at most 60 s.

### SC-2 Understanding

Outcome, before any correction, read against the label: right on 3 of 3.

| Run | Play sheet outcome | Label | Confidence |
|---|---|---|---|
| 1 | one tortilla is flipped over and lands cooked-side up | pancakes are flipped | 0.9 |
| 2 | the skateboard is fully flipped in the air | person doing a skateboard flip | 0.9 |
| 3 | the shot reaches the basket and appears to go through | the ball is in the basket | 0.7 |

Run 1 says tortilla for pancake, as in the cached runs; the owner accepted that. Three clips is no share: T003's 13 of 16 repeats on eight clips stays the reported figure.

Key moment: the picks came from the labels, not from someone choosing on the card, so the share of picks that are offered moments cannot be reported from these runs. As a proxy, the labelled moment is within 0.25 s of an offered moment on 2 of 3 (run 1: 2.233 s, run 2: 6.2 s). On run 3 the nearest offered moment is 3.6 s, 0.7 s from the label, so the picker would have to use the storyboard. The proposed frame is not kept once a pick replaces it, so it is not compared here.

### SC-4 Timing

Target: within 150 ms. Both key lines measured 5 ms from target on all three runs, six onsets in all.

### SC-5 Cost

Target: at most 0.25 USD a run, no run over 1.00 USD.

| Run | Analysis (S2) | Script (S5) | Voice (S7) | Total |
|---|---|---|---|---|
| 1 | 0.0131 | 0.0032 | 0.0240 | 0.0404 |
| 2 | 0.0216 | 0.0031 | 0.0281 | 0.0527 |
| 3 | 0.0377 | 0.0041 | 0.0268 | 0.0686 |

### Sound

All three: -14.1 LUFS and -1.9 dBTP on the finished video, after the limiter change in `880a73c`.

### Things that went less than perfectly

- Run 1: the voice stage dropped line l3 (5 of 6 lines kept).
- Run 2: the clip has no sound, so S1 reported `degraded` ("no sound: motion only") and worked on motion alone.
- Run 3: two overview sheets (`o2_candidate`, `o3_candidate`) hit a read timeout and were left out of S2, which made it the slowest analysis, and its overall confidence was the lowest (0.42; runs 1 and 2 were 0.54). The run still finished, on the remaining sheets.
- `cost.json` shows `pre_checkpoint_usd` 0.0 on all three runs although S2 spent money before the card: the field is set when the ledger is created and never updated. The cap itself sums the calls and is not affected.

### Not measured

- The median of 10 runs. Three runs are not the SC-1 sample, so treat these as rehearsal figures, not a result.
- The page. These runs used the command line with `--pick`, so upload over the network, the time a person spends on the card and any delay in the page itself are not included.
- A clip of exactly 20 s. The clips were 16.7 to 20.2 s.
- The 30-clip evaluation set behind SC-2 (T025, deferred).
- Whether the replays land on the climax to a viewer's eye. Nobody has watched these three videos; the timing figures come from measured onsets.
