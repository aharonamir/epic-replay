Text profile `qwen3-5-4b-local` (Qwen3.5 4B (local), model `qwen3.5:4b`, tool calls yes), measured by `pc/tools/commentator_check.py`. Calls are free.

Accepted = the agent handed in a script that passed the code checks within its iteration limit (otherwise the coded fallback finished it: degraded). Rejected = drafts the checks refused on the way, and the lines in them. Final failures = what S6 finds in the finished script.

| target | repeat | status | accepted by the agent | agent calls | drafts rejected | lines rejected in agent drafts | lines rejected in the one-call draft | lines written by code | final failures | lines | seconds |
|---|---|---|---|---|---|---|---|---|---|---|---|
| sample_run | 0 | degraded | False | 0 | 0 | 0 | 14 | 7 | 0 | 7 | 6.0 |
| sample_run | 1 | degraded | False | 0 | 0 | 0 | 14 | 7 | 0 | 7 | 11.9 |
| sample_run | 2 | degraded | False | 1 | 1 | 7 | 14 | 7 | 0 | 7 | 11.6 |
| fk_1 | 0 | degraded | False | 5 | 5 | 58 | 12 | 6 | 0 | 6 | 14.5 |
| fk_1 | 1 | degraded | False | 3 | 3 | 24 | 12 | 6 | 0 | 6 | 17.6 |
| fk_1 | 2 | degraded | False | 0 | 0 | 0 | 12 | 6 | 0 | 6 | 15.4 |
| basket_ball_shot | 0 | degraded | False | 0 | 0 | 0 | 14 | 7 | 0 | 7 | 5.0 |
| basket_ball_shot | 1 | degraded | False | 2 | 2 | 23 | 14 | 7 | 0 | 7 | 21.8 |
| basket_ball_shot | 2 | degraded | False | 1 | 1 | 9 | 14 | 7 | 0 | 7 | 12.9 |

0/9 runs accepted by the agent; mean 13.0 s a run; 9/9 finished scripts pass S6.

- sample_run r0: the agent submitted no script that passes; generic lines for l1, l2, l3, l4, l5, l6, l7
- sample_run r1: the agent submitted no script that passes; generic lines for l1, l2, l3, l4, l5, l6, l7
- sample_run r2: the agent submitted no script that passes; generic lines for l1, l2, l3, l4, l5, l6, l7
- fk_1 r0: the agent submitted no script that passes; generic lines for l1, l2, l3, l4, l5, l6
- fk_1 r1: the agent submitted no script that passes; generic lines for l1, l2, l3, l4, l5, l6
- fk_1 r2: the agent submitted no script that passes; generic lines for l1, l2, l3, l4, l5, l6
- basket_ball_shot r0: the agent submitted no script that passes; generic lines for l1, l2, l3, l4, l5, l6, l7
- basket_ball_shot r1: the agent submitted no script that passes; generic lines for l1, l2, l3, l4, l5, l6, l7
- basket_ball_shot r2: the agent submitted no script that passes; generic lines for l1, l2, l3, l4, l5, l6, l7
