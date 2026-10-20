Signal candidates per clip: 5 (the design uses 3).

Strict: climax_t only. Any: the nearest valid moment (climax_t or other_climax_t). Errors in frames; no-snap is the answer before the sound snap.

| clip | label (s) | near a candidate | repeat | outcome (model) | outcome (owner) | method | answer (s) | err strict | err any | no-snap strict | no-snap any | within tol strict | within tol any | agreed | retries | s | USD |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| FlippingPancakes.mp4 | [2, 3.574] | True | 0 | The tortilla is flipped in the air and lands back on the gri | right | sheets+sound | 1.97 | -1 | -1 | 6 | 6 | True | True | False | 1 | 109.6 | 0.02757 |
| FlippingPancakes.mp4 | [2, 3.574] | True | 1 | the pancake is flipped and lands back on the griddle | right | sheets+sound | 4.0 | 60 | 13 | 58 | 11 | False | False | False | 0 | 110.6 | 0.0273 |
| Skateboard_trick.mp4 | [6] | True | 0 | lands the trick and continues riding | right | sheets | 12.23 | 187 | 187 | 187 | 187 | False | False | False | 0 | 80.2 | 0.01866 |
| Skateboard_trick.mp4 | [6] | True | 1 | rider pops the front of the skateboard to perform a trick | right | sheets | 5.73 | -8 | -8 | -8 | -8 | True | True | False | 0 | 97.7 | 0.02106 |
| SlowMotion-skater.mp4 | [6] | False | 0 | lands the trick and rides away | right | sheets | 10.73 | 142 | 142 | 142 | 142 | False | False | True | 0 | 57.2 | 0.01368 |
| SlowMotion-skater.mp4 | [6] | False | 1 | lands the trick and rides away successfully | right | sheets | 10.63 | 139 | 139 | 139 | 139 | False | False | True | 0 | 70.9 | 0.01756 |
| basket_ball_shot.mp4 | [2.9, 8.0] | True | 0 | The ball reaches the rim and bounces away; the person in bla | right | sheets | 7.6 | 141 | -12 | 141 | -12 | False | False | False | 1 | 0.0 | 0.04288 |
| basket_ball_shot.mp4 | [2.9, 8.0] | True | 1 | he scores a basket | right | sheets+sound | 7.93 | 151 | -2 | 158 | 5 | False | True | False | 0 | 77.4 | 0.05327 |
| bottle flip.mp4 | [2.0] | True | 0 | The bottle lands successfully and remains standing upright o | right | sheets+sound | 1.13 | -26 | -26 | -28 | -28 | False | False | True | 0 | 53.6 | 0.01343 |
| bottle flip.mp4 | [2.0] | True | 1 | the bottle lands upright and is picked up | right | sheets+sound | 1.13 | -26 | -26 | -27 | -27 | False | False | True | 0 | 52.2 | 0.01336 |
| dog_catch_ball.mp4 | [13.0] | True | 0 | The dog reaches the bottom right corner of the frame | wrong | sheets+sound | 13.7 | 21 | 21 | 28 | 28 | False | False | False | 0 | 64.3 | 0.01574 |
| dog_catch_ball.mp4 | [13.0] | True | 1 | the dog catches the ball | right | sheets | 10.17 | -85 | -85 | -85 | -85 | False | False | False | 0 | 153.0 | 0.04083 |
| fk_1.mp4 | [16.0] | True | 0 | the ball travels towards the net while the goalkeeper watche | right | sheets+sound | 17.07 | 32 | 32 | 39 | 39 | False | False | False | 0 | 200.7 | 0.0493 |
| fk_1.mp4 | [16.0] | True | 1 | the ball enters the goal net | right | sheets+sound | 18.97 | 89 | 89 | 89 | 89 | False | False | False | 0 | 142.4 | 0.03097 |
| fk_miss.mp4 | [3.0] | False | 0 | The ball is kicked toward the goal. | wrong | sheets+sound | 6.93 | 118 | 118 | 121 | 121 | False | False | False | 0 | 310.9 | 0.07458 |
| fk_miss.mp4 | [3.0] | False | 1 | the ball is crossed into the penalty area where players cont | wrong | sheets+sound | 4.07 | 32 | 32 | 32 | 32 | False | False | False | 0 | 220.4 | 0.0532 |

Per clip: retries are second calls after a reply that did not fit the schema; fallbacks are repeats where the retry failed too (plan.md 4.3: the strongest signal candidate, low confidence), left out of the accuracy share.

| clip | repeats | fallbacks | retries | outcome right | within tol strict | within tol any |
|---|---|---|---|---|---|---|
| FlippingPancakes.mp4 | 2 | 0 | 1 | 2/2 | 1/2 | 1/2 |
| Skateboard_trick.mp4 | 2 | 0 | 0 | 2/2 | 1/2 | 1/2 |
| SlowMotion-skater.mp4 | 2 | 0 | 0 | 2/2 | 0/2 | 0/2 |
| basket_ball_shot.mp4 | 2 | 0 | 1 | 2/2 | 0/2 | 1/2 |
| bottle flip.mp4 | 2 | 0 | 0 | 2/2 | 0/2 | 0/2 |
| dog_catch_ball.mp4 | 2 | 0 | 0 | 1/2 | 0/2 | 0/2 |
| fk_1.mp4 | 2 | 0 | 0 | 2/2 | 0/2 | 0/2 |
| fk_miss.mp4 | 2 | 0 | 0 | 0/2 | 0/2 | 0/2 |
| fk_save.mp4 | not run | | | | | |
| total | 16 | 0 | 2 | 13/16 | 2/16 | 3/16 |
