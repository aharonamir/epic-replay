Vision profile `gemma-4-e2b-local` (Gemma 4 E2B (local), model `gemma4:e2b`), measured by `pc/tools/spotter_check.py --profile`, 2 repeats per clip. Calls are free.

Strict: `climax_t` only. Any: the nearest valid moment (`climax_t` or `other_climax_t`). Errors in frames; within tol = within EPIC_KEYFRAME_TOL_S. Schema fit = calls answered after the one retry, of six (five sheets and the events call). `s` = S2 and S3 in all; call = seconds per call (mean, longest).

| clip | repeat | outcome (model) | outcome (owner) | method | answer (s) | err strict | err any | within tol strict | within tol any | candidate near strict | candidate near any | schema fit | retries | call s | s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| FlippingPancakes.mp4 | 0 | The food item is being handled and presented, possibly being flipped or served. |  | sheets+sound | 4.0 | 60 | 13 | False | False | True | True | 6/6 | 0 | 4.0, 6.3 | 6.4 |
| FlippingPancakes.mp4 | 1 | The food is being flipped and cooked until golden brown, |  | sheets | 3.77 | 53 | 6 | False | True | True | True | 6/6 | 0 | 2.5, 5.0 | 5.1 |
| Skateboard_trick.mp4 | 0 | The person maintains balance while moving on the skateboard. |  | sheets | 4.17 | -55 | -55 | False | False | True | True | 6/6 | 0 | 3.3, 6.0 | 6.2 |
| Skateboard_trick.mp4 | 1 | The person finishes their movement, standing on the skateboard. |  | sheets | 4.17 | -55 | -55 | False | False | True | True | 6/6 | 0 | 2.6, 5.1 | 5.3 |
| SlowMotion-skater.mp4 | 0 | The action continues as the person moves across the frame. |  | sheets+sound | 11.1 | 153 | 153 | False | False | False | False | 6/6 | 0 | 3.1, 5.6 | 5.8 |
| SlowMotion-skater.mp4 | 1 | The person continues to skate across the pavement. |  | sheets+sound | 11.1 | 153 | 153 | False | False | False | False | 6/6 | 0 | 2.9, 5.4 | 5.6 |
| basket_ball_shot.mp4 | 0 | The sequence shows different actions of the same person interacting with the ball during p |  | sheets+sound | 9.47 | 197 | 44 | False | False | False | True | 6/6 | 0 | 4.5, 6.6 | 6.8 |
| basket_ball_shot.mp4 | 1 | The person continues to run or move after the kick. |  | sheets | 9.27 | 191 | 38 | False | False | False | True | 6/6 | 0 | 2.8, 5.2 | 5.4 |
| bottle flip.mp4 | 0 | Water is being sprayed onto the person. |  | sheets+sound | 12.07 | 302 | 302 | False | False | True | True | 6/6 | 0 | 4.1, 6.2 | 6.4 |
| bottle flip.mp4 | 1 | The blue object lands on the ground. |  | sheets | 0.0 | -60 | -60 | False | False | True | True | 6/6 | 0 | 2.9, 5.5 | 5.7 |
| dog_catch_ball.mp4 | 0 | The German Shepherd is successfully catching the ball in each frame shown. |  | sheets | 5.77 | -217 | -217 | False | False | False | False | 6/6 | 0 | 2.9, 5.8 | 5.9 |
| dog_catch_ball.mp4 | 1 | He is really good in catching the ball |  | sheets | 3.5 | -285 | -285 | False | False | False | False | 6/6 | 0 | 2.4, 4.8 | 4.9 |
| fk_1.mp4 | 0 | The sequence shows various moments of an individual interacting with the ball on the field |  | sheets+sound | 7.23 | -263 | -263 | False | False | False | False | 6/6 | 0 | 2.8, 5.7 | 5.9 |
| fk_1.mp4 | 1 | The person controls the ball or is moving with it. |  | sheets+sound | 7.23 | -263 | -263 | False | False | False | False | 6/6 | 0 | 2.8, 4.9 | 5.1 |
| fk_miss.mp4 | 0 | The frame shows various actions of players on the field. |  | sheets+sound | 5.5 | 75 | 75 | False | False | False | False | 6/6 | 0 | 3.9, 6.7 | 6.9 |
| fk_miss.mp4 | 1 | The action continues with players positioning themselves for the next play or movement pas |  | sheets+sound | 12.13 | 274 | 274 | False | False | False | False | 6/6 | 0 | 2.9, 5.6 | 5.7 |

| total | repeats | calls answered | retries | within tol strict | within tol any | candidate near strict | candidate near any | owner right | seconds per repeat (mean) | cost |
|---|---|---|---|---|---|---|---|---|---|---|
| gemma-4-e2b-local | 16 | 96/96 | 0 | 0/16 | 1/16 | 6/16 | 8/16 | 0/0 | 5.8 | 0.0000 USD |
