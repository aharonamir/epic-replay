Text profile `gemma-4-e2b-local` (Gemma 4 E2B (local), model `gemma4:e2b`, tool calls yes), measured by `pc/tools/commentator_check.py`. Calls are free.

Accepted = the agent handed in a script that passed the code checks within its iteration limit (otherwise the coded fallback finished it: degraded). Rejected = drafts the checks refused on the way, and the lines in them. Final failures = what S6 finds in the finished script.

| target | repeat | status | accepted by the agent | agent calls | drafts rejected | lines rejected in agent drafts | lines rejected in the one-call draft | lines written by code | final failures | lines | seconds |
|---|---|---|---|---|---|---|---|---|---|---|---|
| sample_run | 0 | degraded | False | 2 | 1 | 8 | 6 | 5 | 0 | 7 | 13.8 |
| sample_run | 1 | degraded | False | 2 | 1 | 9 | 6 | 4 | 0 | 7 | 13.0 |
| sample_run | 2 | degraded | False | 2 | 1 | 10 | 5 | 4 | 0 | 7 | 12.7 |
| fk_1 | 0 | degraded | False | 6 | 3 | 15 | 6 | 3 | 0 | 6 | 19.0 |
| fk_1 | 1 | degraded | False | 6 | 1 | 8 | 5 | 3 | 0 | 6 | 16.9 |
| fk_1 | 2 | degraded | False | 5 | 1 | 6 | 7 | 3 | 0 | 6 | 15.6 |
| basket_ball_shot | 0 | degraded | False | 4 | 2 | 16 | 6 | 4 | 0 | 7 | 18.4 |
| basket_ball_shot | 1 | degraded | False | 4 | 1 | 6 | 5 | 4 | 0 | 7 | 15.5 |
| basket_ball_shot | 2 | degraded | False | 2 | 1 | 6 | 5 | 4 | 0 | 7 | 12.3 |

0/9 runs accepted by the agent; mean 15.2 s a run; 9/9 finished scripts pass S6.

- sample_run r0: the agent submitted no script that passes; generic lines for l2, l3, l4, l6, l7
- sample_run r1: the agent submitted no script that passes; generic lines for l2, l3, l4, l7
- sample_run r2: the agent submitted no script that passes; generic lines for l2, l3, l4, l7
- fk_1 r0: the agent submitted no script that passes; generic lines for l2, l3, l6
- fk_1 r1: the agent submitted no script that passes; generic lines for l2, l3, l6
- fk_1 r2: the agent submitted no script that passes; generic lines for l2, l3, l6
- basket_ball_shot r0: the agent submitted no script that passes; generic lines for l2, l3, l4, l7
- basket_ball_shot r1: the agent submitted no script that passes; generic lines for l2, l3, l4, l7
- basket_ball_shot r2: the agent submitted no script that passes; generic lines for l2, l3, l4, l7
