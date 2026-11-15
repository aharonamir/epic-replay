Vision profile `qwen3-5-4b-local` (Qwen3.5 4B (local), model `qwen3.5:4b`), measured by `pc/tools/spotter_check.py --profile`, 2 repeats per clip. Calls are free.

Strict: `climax_t` only. Any: the nearest valid moment (`climax_t` or `other_climax_t`). Errors in frames; within tol = within EPIC_KEYFRAME_TOL_S. Schema fit = calls answered after the one retry, of six (five sheets and the events call). `s` = S2 and S3 in all; call = seconds per call (mean, longest).

| clip | repeat | outcome (model) | outcome (owner) | method | answer (s) | err strict | err any | within tol strict | within tol any | candidate near strict | candidate near any | schema fit | retries | call s | s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| FlippingPancakes.mp4 | 0 | the pancakes are cooked and ready to be served | wrong | sheets+sound | 5.0 | 90 | 43 | False | False | True | True | 3/6 | 2 | 13.6, 18.0 | 18.2 |
| FlippingPancakes.mp4 | 1 | the person lifts one pancake to flip or remove it while another cooks nearby | right | sheets+sound | 1.3 | -21 | -21 | False | False | True | True | 3/6 | 1 | 4.8, 9.5 | 16.6 |
| Skateboard_trick.mp4 | 0 | completes successfully as wheels touch ground again | right | sheets | 6.07 | 2 | 2 | True | True | True | True | 5/6 | 2 | 9.8, 20.5 | 20.7 |
| Skateboard_trick.mp4 | 1 | stopped | wrong | sheets | 11.47 | 164 | 164 | False | False | True | True | 3/6 | 1 | 8.2, 15.3 | 15.5 |
| SlowMotion-skater.mp4 | 0 | the skater completes the maneuver smoothly and continues riding forward | right | sheets+sound | 2.57 | -103 | -103 | False | False | False | False | 4/6 | 0 | 3.1, 4.7 | 14.3 |
| SlowMotion-skater.mp4 | 1 | the skateboard lands but the person does not catch it and falls off the board immediately  | wrong | sheets+sound | 11.23 | 157 | 157 | False | False | False | False | 4/6 | 0 | 3.7, 7.8 | 12.3 |
| basket_ball_shot.mp4 | 0 |  | wrong | sheets+sound | 14.13 | 337 | 184 | False | False | False | True | 5/6 | 0 | 5.7, 10.2 | 13.6 |
| basket_ball_shot.mp4 | 1 | the two individuals complete their action by successfully or unsuccessfully throwing the b | right | sheets+sound | 7.93 | 151 | -2 | False | True | False | True | 5/6 | 0 | 4.5, 8.6 | 12.2 |
| bottle flip.mp4 | 0 | The bottle lands upright on the concrete surface and stays there; no damage or movement fo | right | sheets | 0.0 | -60 | -60 | False | False | True | True | 4/6 | 1 | 7.3, 11.6 | 16.5 |
| bottle flip.mp4 | 1 | the bottle shatters into multiple pieces | wrong | sheets | 18.4 | 492 | 492 | False | False | True | True | 4/6 | 2 | 7.1, 12.6 | 16.0 |
| dog_catch_ball.mp4 | 0 | He successfully grabs the frisbee with his mouth and holds it while standing still. | wrong | sheets | 6.5 | -195 | -195 | False | False | False | False | 4/6 | 1 | 7.6, 12.5 | 18.1 |
| dog_catch_ball.mp4 | 1 | The dog successfully catches the ball mid-air. | right | sheets | 8.13 | -146 | -146 | False | False | False | False | 3/6 | 0 | 5.7, 8.6 | 18.7 |
| fk_1.mp4 | 0 | The ball goes into the goal net as players watch or move away from its path. | right | sheets+sound | 17.53 | 46 | 46 | False | False | False | False | 5/6 | 0 | 4.5, 7.0 | 14.3 |
| fk_1.mp4 | 1 | the player hits and scores on Goal | right | sheets+sound | 17.53 | 46 | 46 | False | False | False | False | 5/6 | 0 | 3.0, 5.0 | 12.6 |
| fk_miss.mp4 | 0 | The pink-clad goalkeeper dives for the shot but fails to save it; another yellow-jerseyed  |  | sheets+sound | 12.9 | 297 | 297 | False | False | False | False | 5/6 | 2 | 9.2, 14.3 | 17.2 |
| fk_miss.mp4 | 1 | goal scored with an assisted goal assist by his teammate who passes to him through |  | sheets+sound | 12.33 | 280 | 280 | False | False | False | False | 3/6 | 1 | 7.3, 17.0 | 17.2 |

| total | repeats | calls answered | retries | within tol strict | within tol any | candidate near strict | candidate near any | owner right | seconds per repeat (mean) | cost |
|---|---|---|---|---|---|---|---|---|---|---|
| qwen3-5-4b-local | 16 | 65/96 | 13 | 1/16 | 2/16 | 6/16 | 8/16 | 8/14 | 15.9 | 0.0000 USD |
