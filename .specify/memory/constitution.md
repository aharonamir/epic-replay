# Epic Replay Constitution

Version 1.3 (2026-10-11). These principles override convenience. A change to any of them is a spec change: update this file, then spec.md, plan.md and tasks.md in the same commit.

## I. The play is the source of truth

Commentary, headlines and graphics may state only what is in the play sheet the user confirmed, plus what the user typed. Nothing is invented: no names, scores, distances, history or statistics. When the system is unsure, it asks the user or says less.

## II. Code does what code can do

Timing, cutting, mixing, word budgets, reference checks, captions and assembly are deterministic code with tests. Models do three things only: choose which frames to look at, describe what they show as structured data, and write text. The tools they call, the limits on those tools and the validators of what they submit are code. A model is never asked to do arithmetic on timestamps or to judge its own output without a code check running first.

## III. A person confirms before the costly part

The user sees what the system believes happened and confirms or corrects it before any script, voice or render work starts. The estimated cost of the run is shown at the same point.

## IV. Picture drives timing

The storyboard fixes the picture timeline. Commentary is written and fitted to that timeline. Audio never changes the length of a picture segment.

## V. Providers are swappable

Every paid capability sits behind an adapter with a primary and a fallback. Adapters record and replay calls so tests and demos run without spending money. No provider SDK is imported outside its adapter.

## VI. Honest output

Every exported video carries a visible "AI commentary" label, in every narration style. Narrators are house voices: no imitation of the voice, name or catchphrases of a real commentator, news presenter or documentary narrator, and no look-alike of the graphics of a real league, broadcaster or news outlet. The news style reports only the play in the clip.

## VII. People in the clip are protected

Only footage the user has the right to use is accepted. People are referred to by a name the user gave or by neutral visible descriptors such as kit colour or playing role. The system never infers or mentions identity, age, gender, ethnicity, body or ability. Children are likely to be in the footage and get no weaker protection than adults: no stage says or guesses that someone is a child, and the person uploading confirms they are the parent or guardian or have permission. Injury, fights, distress, humiliation and anyone undressed stop the run. Only still frames leave the machine, to one provider, on terms that exclude training use; where the chosen provider's terms do not, that is a recorded decision of the owner and the page says where the frames go. The user can delete a run at once.

## VIII. Every run is replayable and costed

Each stage writes its output as a file under the run folder. Any stage can be re-run from stored inputs. Every paid call is logged with its estimate and its actual cost, and caps are enforced before the call is made.

## IX. A video always comes out

Each paid stage has a free or cheaper fallback, down to a version with frame-blended slow motion and generic commentary. A provider outage degrades quality; it does not end the run.

## X. A style changes the telling, never the facts

A narration style is data: persona, voice, delivery, pace, sound and skin. It cannot add facts, move the picture, or switch off a check. Two styles of one play must agree on what happened.

## Governance

- Gate reviews (plan.md section 6) check these principles explicitly.
- A task that would break a principle is stopped and raised, not worked around.
