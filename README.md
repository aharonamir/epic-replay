# Epic Replay - Spec Kit

You film a moment worth replaying: a goal, a trick, a dog's catch, a pancake flip. It does not have to be sport. On one web page you upload the clip and choose who narrates: a football commentator, a news reporter or a nature-documentary narrator. The app watches the clip, shows its progress, asks you to confirm what happened, writes and voices the narration in that style, adds a slow-motion replay and graphics, and returns a short video. One more click tells the same moment in another style.

This folder is the design, ready to drop into a new repository. No code yet. Revision 5 (2026-10-11): one person, ten working days; ReAct agents on openJiuwen agent-core, running on DeepSeek; the page has an approved visual design.

## Read in this order

| File | What it answers |
|------|-----------------|
| [specs/001-epic-replay/spec.md](specs/001-epic-replay/spec.md) | What the product does, how success is measured, and (section 10) what the one-person build defers |
| [specs/001-epic-replay/tasks.md](specs/001-epic-replay/tasks.md) | 34 tasks in day order, 78 hours, a done-when line each; six deferred tasks; the cut list |
| [specs/001-epic-replay/plan.md](specs/001-epic-replay/plan.md) | The stages, the narration styles, the schedule and gates, the risks |
| [specs/001-epic-replay/research.md](specs/001-epic-replay/research.md) | Which providers fit each stage, what they cost, what is still unconfirmed |
| [specs/001-epic-replay/contracts/ui.md](specs/001-epic-replay/contracts/ui.md) | The page: its four states, layout and wording |
| [specs/001-epic-replay/contracts/design.md](specs/001-epic-replay/contracts/design.md) | How the page looks: colours, type, measurements, parts; the canvas files are in [design-canvas/](specs/001-epic-replay/contracts/design-canvas/README.md) |
| [specs/001-epic-replay/data-model.md](specs/001-epic-replay/data-model.md) | The files a run produces |
| [specs/001-epic-replay/contracts/](specs/001-epic-replay/contracts/) | JSON Schemas with examples (including the three style packs), stage contracts, adapter interfaces, HTTP API |
| [specs/001-epic-replay/quickstart.md](specs/001-epic-replay/quickstart.md) | Target commands, and how to film clips that work |
| [.specify/memory/constitution.md](.specify/memory/constitution.md) | Ten principles the build may not break |
| [START-PROMPT.md](START-PROMPT.md) | Prompt for the first Claude Code session |

## The ten days

| Day | Built | Exists at the end |
|-----|-------|-------------------|
| 1 | Repo, schemas, fixture, adapters, guards, starter clips | A fixture run that validates |
| 2 | agent-core check, accuracy test on frames, voice choice | Gate 0: the numbers the plan depends on, and the privacy decision |
| 3-4 | Intake, analysis, key moment, storyboard, script, code checks | Clip to checked script on the command line |
| 5-7 | Slow motion, voice, sound, graphics, compose | Clip to video on the command line |
| 7-8 | Flow, play card, the page | Clip to video on the page, one narrator |
| 9 | Two more narrators, re-telling, privacy checks | Three narrators |
| 10 | Evaluation, tuning, rehearsal, presentation | Ready to present |

78 of 80 hours are planned. There is no slack; the order of work is the protection. A working demo exists from day 8, and what is built last is what is cut first.

## The pipeline, as built in the ten days

| Stage | What happens | Who does it | Provider |
|-------|--------------|-------------|----------|
| S0 Intake | Validate, probe, make a working copy | Code | FFmpeg |
| S2 Analysis | Look at numbered frames of the clip, then a second independent look | Spotter, a ReAct agent with `look` and `zoom` tools | DeepSeek `deepseek-flash` through agent-core |
| S3 Key moment | Zoom in frame by frame on the high point | Spotter agent and code | DeepSeek `deepseek-flash` |
| Checkpoint A | You confirm or correct what happened | Human | none |
| S4 Storyboard | Lay out title, live, replay, end card | Code | none |
| S5 Script | Write the lines to timed slots, in the narrator's persona, checking each draft | Code and Commentator, a ReAct agent with a `check_lines` tool | DeepSeek `deepseek-flash` through agent-core |
| S6 Checks | The same ten code checks, run again on what the agent submitted | Code | none |
| S7 Voice | One take per line in the narrator's voice, fitted and placed | Code and provider | Gemini 3.8 Flash TTS (DeepSeek has no speech model); ElevenLabs only if needed for the shout |
| S8 Sound | The narrator's sound set, mixed | Code | Library made once with ElevenLabs Sound Effects and Lyria 3 Clip |
| S9 Visuals | Slow motion, and graphics in the narrator's skin | Code | Real frames or FFmpeg blending; HTML templates |
| S10 Compose | Assemble and export | Code | FFmpeg |

A plain Python runner moves a run from stage to stage. DeepSeek takes still images, not video or sound, so the clip is analysed as numbered frames and is never heard.

Deferred from the full design: the Verifier agent, the signal scan (S1), fallback providers, frame interpolation, the score bug, captions, the 30-clip evaluation set.

## Narration styles

A style is a data file, not code. It sets the persona and writing rules, the voice, how each kind of line is delivered, the pace, the sounds and the graphics skin. It cannot change the facts, the picture or the checks.

| | Football commentator | News reporter | Nature documentary |
|---|---|---|---|
| Sounds like | Excited play-by-play that treats any moment as the big match | A calm bulletin read with a straight face | A hushed, fond observer |
| Key line | Shouted at once | Stated as fact, a beat later | Almost whispered, later still |
| Around it | Crowd and roar | No crowd, bulletin stings | Outdoor ambience, quiet strings |

## Children in the footage

- The person uploading confirms they are the parent or guardian, or have permission.
- Only still frames leave the machine, to one provider. Voice and script stages send text only. Slow motion is made on the machine.
- No stage says or guesses that someone is a child, or remarks on anyone's body or ability.
- A clip showing someone hurt, upset, humiliated or undressed is not narrated.
- A run can be deleted at once from the page.
- **Not settled:** the spec requires that the provider's terms exclude training on what it is sent. As far as I could confirm, DeepSeek's do not, and its data is stored in China. See "Still open".

## Decided

- One person, two weeks.
- Any short moment, not only sport.
- Children are likely to appear.
- The one-tap confirmation of what happened stays.
- The look of the page: dark, broadcast-style, coloured by the chosen narrator.
- Three narration styles in the MVP.
- ReAct agents on openJiuwen agent-core; a plain Python runner for the flow; no jiuwenswarm.
- DeepSeek as the model provider, using `deepseek-flash` (the successor to the retired `deepseek-v4-flash-vision-exp`).

## Still open

1. Which provider sees frames of clips with children in them? Options, in spec.md section 8: keep the agents on DeepSeek and send frames to a provider with a no-training commitment (recommended; one line of configuration); use DeepSeek for everything and record the waiver; or look at frames on your own machines.
2. Does "two weeks" leave ten full working days? With nine, the Nature documentary pack and the sample player go.
