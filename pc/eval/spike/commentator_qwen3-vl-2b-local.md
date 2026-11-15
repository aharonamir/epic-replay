Text profile `qwen3-vl-2b-local` (Qwen3-VL 2B Instruct (local), model `qwen3-vl:2b-instruct`, tool calls no: the structured draft is used), measured by `pc/tools/commentator_check.py`. Calls are free.

Accepted = the agent handed in a script that passed the code checks within its iteration limit (otherwise the coded fallback finished it: degraded). Rejected = drafts the checks refused on the way, and the lines in them. Final failures = what S6 finds in the finished script.

| target | repeat | status | accepted by the agent | agent calls | drafts rejected | lines rejected in agent drafts | lines rejected in the one-call draft | lines written by code | final failures | lines | seconds |
|---|---|---|---|---|---|---|---|---|---|---|---|
| sample_run | 0 | degraded | False | 0 | 0 | 0 | 7 | 7 | 0 | 7 | 2.4 |
| sample_run | 1 | degraded | False | 0 | 0 | 0 | 6 | 6 | 0 | 7 | 2.1 |
| sample_run | 2 | degraded | False | 0 | 0 | 0 | 7 | 7 | 0 | 7 | 2.0 |
| fk_1 | 0 | degraded | False | 0 | 0 | 0 | 7 | 4 | 0 | 6 | 1.4 |
| fk_1 | 1 | degraded | False | 0 | 0 | 0 | 8 | 6 | 0 | 6 | 1.6 |
| fk_1 | 2 | degraded | False | 0 | 0 | 0 | 8 | 6 | 0 | 6 | 2.1 |
| basket_ball_shot | 0 | degraded | False | 0 | 0 | 0 | 6 | 6 | 0 | 7 | 2.3 |
| basket_ball_shot | 1 | degraded | False | 0 | 0 | 0 | 7 | 7 | 0 | 7 | 2.2 |
| basket_ball_shot | 2 | degraded | False | 0 | 0 | 0 | 7 | 7 | 0 | 7 | 1.8 |

0/9 runs accepted by the agent; mean 2.0 s a run; 9/9 finished scripts pass S6.

- sample_run r0: Qwen3-VL 2B Instruct (local) does not take tool calls: the structured draft was used; generic lines for l1, l2, l3, l4, l5, l6, l7
- sample_run r1: Qwen3-VL 2B Instruct (local) does not take tool calls: the structured draft was used; generic lines for l2, l3, l4, l5, l6, l7
- sample_run r2: Qwen3-VL 2B Instruct (local) does not take tool calls: the structured draft was used; generic lines for l1, l2, l3, l4, l5, l6, l7
- fk_1 r0: Qwen3-VL 2B Instruct (local) does not take tool calls: the structured draft was used; generic lines for l2, l3, l5, l6
- fk_1 r1: Qwen3-VL 2B Instruct (local) does not take tool calls: the structured draft was used; generic lines for l1, l2, l3, l4, l5, l6
- fk_1 r2: Qwen3-VL 2B Instruct (local) does not take tool calls: the structured draft was used; generic lines for l1, l2, l3, l4, l5, l6
- basket_ball_shot r0: Qwen3-VL 2B Instruct (local) does not take tool calls: the structured draft was used; generic lines for l2, l3, l4, l5, l6, l7
- basket_ball_shot r1: Qwen3-VL 2B Instruct (local) does not take tool calls: the structured draft was used; generic lines for l1, l2, l3, l4, l5, l6, l7
- basket_ball_shot r2: Qwen3-VL 2B Instruct (local) does not take tool calls: the structured draft was used; generic lines for l1, l2, l3, l4, l5, l6, l7
