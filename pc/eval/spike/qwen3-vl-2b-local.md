Vision profile `qwen3-vl-2b-local` (Qwen3-VL 2B Instruct (local), model `qwen3-vl:2b-instruct`), measured by `pc/tools/spotter_check.py --profile`, 2 repeats per clip. Calls are free.

Strict: `climax_t` only. Any: the nearest valid moment (`climax_t` or `other_climax_t`). Errors in frames; within tol = within EPIC_KEYFRAME_TOL_S. Schema fit = calls answered after the one retry, of six (five sheets and the events call). `s` = S2 and S3 in all; call = seconds per call (mean, longest).

| clip | repeat | outcome (model) | outcome (owner) | method | answer (s) | err strict | err any | within tol strict | within tol any | candidate near strict | candidate near any | schema fit | retries | call s | s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| FlippingPancakes.mp4 | 0 | Pancakes are being flipped and cooked over a griddle in a kitchen. |  | sheets | 3.77 | 53 | 6 | False | True | True | True | 6/6 | 0 | 4.8, 7.8 | 8.0 |
| FlippingPancakes.mp4 | 1 | the pancakes are golden brown and the person is flipping them with a spatula |  | sheets | 5.5 | 105 | 58 | False | False | True | True | 6/6 | 0 | 3.2, 4.4 | 4.6 |
| Skateboard_trick.mp4 | 0 | The skateboarder is executing a maneuver, possibly a ollie or kickflip. |  | sheets | 5.67 | -10 | -10 | False | False | True | True | 6/6 | 0 | 4.9, 6.5 | 6.7 |
| Skateboard_trick.mp4 | 1 | The skateboarder successfully performs a trick and lands the maneuver. |  | sheets | 8.37 | 71 | 71 | False | False | True | True | 6/6 | 0 | 2.0, 4.7 | 4.9 |
| SlowMotion-skater.mp4 | 0 |  |  |  |  |  |  |  |  |  |  | 6/6 | 0 | 2.9, 6.5 | 6.7 |
| SlowMotion-skater.mp4 | 1 | the skateboarder performs a trick that results in the board flipping over and landing on t |  | sheets | 5.83 | -5 | -5 | True | True | True | True | 6/6 | 0 | 4.0, 5.6 | 5.8 |
| basket_ball_shot.mp4 | 0 | the man makes the basket |  | sheets+sound | 10.37 | 224 | 71 | False | False | False | True | 6/6 | 0 | 3.0, 6.6 | 6.8 |
| basket_ball_shot.mp4 | 1 | the man shoots the ball towards the basket and it goes in |  | sheets+sound | 9.47 | 197 | 44 | False | False | False | True | 6/6 | 0 | 3.1, 4.8 | 5.1 |
| bottle flip.mp4 | 0 | the person stands still while the sunlight is visible through the trees. |  | sheets | 19.93 | 538 | 538 | False | False | True | True | 6/6 | 0 | 3.0, 5.6 | 5.8 |
| bottle flip.mp4 | 1 | the camera is moving forward toward the bottle, as it appears to be captured in motion thr |  | sheets+sound | 20.07 | 542 | 542 | False | False | True | True | 6/6 | 0 | 2.0, 4.3 | 4.5 |
| dog_catch_ball.mp4 | 0 | The video ends with the dog looking out the window and saying 'Thank you.' |  | sheets+sound | 22.37 | 281 | 281 | False | False | False | False | 6/6 | 0 | 3.6, 5.6 | 5.8 |
| dog_catch_ball.mp4 | 1 | The German Shepherd skillfully catches the ball with precision and speed, indicating an im |  | sheets | 2.37 | -319 | -319 | False | False | False | False | 6/6 | 0 | 3.2, 5.1 | 5.3 |
| fk_1.mp4 | 0 | a player in a yellow uniform is shown running and falling to the ground after a tackle |  | sheets+sound | 22.73 | 202 | 202 | False | False | False | False | 6/6 | 0 | 4.6, 6.2 | 6.4 |
| fk_1.mp4 | 1 | a player in a yellow uniform is being tackled by multiple opponents on the field |  | sheets+sound | 22.47 | 194 | 194 | False | False | False | False | 6/6 | 0 | 2.4, 4.9 | 5.2 |
| fk_miss.mp4 | 0 | a goal is scored |  | sheets+sound | 4.77 | 53 | 53 | False | False | False | False | 5/6 | 0 | 3.2, 6.4 | 7.6 |
| fk_miss.mp4 | 1 | a goal is scored by the team in yellow |  | sheets+sound | 13.23 | 307 | 307 | False | False | False | False | 5/6 | 0 | 1.7, 2.6 | 4.9 |

| total | repeats | calls answered | retries | within tol strict | within tol any | candidate near strict | candidate near any | owner right | seconds per repeat (mean) | cost |
|---|---|---|---|---|---|---|---|---|---|---|
| qwen3-vl-2b-local | 16 | 94/96 | 0 | 1/16 | 2/16 | 7/16 | 9/16 | 0/0 | 5.9 | 0.0000 USD |
