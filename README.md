# Epic Replay - Spec Kit

You film a moment worth replaying: a goal, a trick, a dog's catch, a pancake flip. It does not have to be sport. On one web page you upload the clip and choose who narrates: a football commentator, a news reporter or a nature-documentary narrator. The app watches the clip, shows its progress, asks you to confirm what happened, writes and voices the narration in that style, adds a slow-motion replay and graphics, and returns a short video. One more click tells the same moment in another style.

The design is in `specs/` and the code in `src/`. Revision 7 (2026-10-15): one person, nine working days; the key moment is found with a signal scan and contact sheets; two narrators in the MVP; ReAct agents on openJiuwen agent-core, running on DeepSeek; the page has an approved visual design.

## Read in this order

| File | What it answers |
|------|-----------------|
| [specs/001-epic-replay/spec.md](specs/001-epic-replay/spec.md) | What the product does, how success is measured, and (section 10) what the one-person build defers |
| [specs/001-epic-replay/tasks.md](specs/001-epic-replay/tasks.md) | The tasks in day order, 43 hours from 15 October, a done-when line each; the deferred tasks; the cut list |
| [specs/001-epic-replay/plan.md](specs/001-epic-replay/plan.md) | The stages, the narration styles, the schedule and gates, the risks |
| [specs/001-epic-replay/research.md](specs/001-epic-replay/research.md) | Which providers fit each stage, what they cost, what is still unconfirmed |
| [specs/001-epic-replay/contracts/ui.md](specs/001-epic-replay/contracts/ui.md) | The page: its four states, layout and wording |
| [specs/001-epic-replay/contracts/design.md](specs/001-epic-replay/contracts/design.md) | How the page looks: colours, type, measurements, parts; the canvas files are in [design-canvas/](specs/001-epic-replay/contracts/design-canvas/README.md) |
| [specs/001-epic-replay/data-model.md](specs/001-epic-replay/data-model.md) | The files a run produces |
| [specs/001-epic-replay/contracts/](specs/001-epic-replay/contracts/) | JSON Schemas with examples (including the three style packs), stage contracts, adapter interfaces, HTTP API |
| [specs/001-epic-replay/quickstart.md](specs/001-epic-replay/quickstart.md) | Target commands, and how to film clips that work |
| [.specify/memory/constitution.md](.specify/memory/constitution.md) | Ten principles the build may not break |
| [START-PROMPT.md](START-PROMPT.md) | Prompt for the first Claude Code session |

## The nine days

| Day | Date | Built | Exists at the end |
|-----|------|-------|-------------------|
| 1 | Mon 12 Oct | Repo, schemas, fixture, adapters, guards, starter clips | A fixture run that validates |
| 2 | Tue 13 Oct | agent-core check, voice choice | Framework and voices chosen |
| 3-4 | Wed 14 and Thu 15 Oct | Key-moment spikes: numbered frames, then contact sheets | Gate 0 |
| 5 | Sun 18 Oct | Signal scan, intake, Spotter on sheets, style loader | Clip to play sheet on the command line |
| 6 | Mon 19 Oct | Validation, storyboard, Commentator, code checks, FFmpeg toolkit | Clip to checked script on the command line |
| 7 | Tue 20 Oct | Slow motion, voice placed by key word, sound, mix, graphics | The parts of the video |
| 8 | Wed 21 Oct | Compose, flow, play card, News reporter pack | Clip to video; a second narrator |
| 9 | Thu 22 Oct | The page, end to end, privacy check, demo mode, presentation | Ready to present on Sat 24 Oct |

43 hours are planned from the afternoon of 15 October, with none spare. The order of work is the protection, and what is built last is cut first (tasks.md).

## The pipeline, as built in the MVP

| Stage | What happens | Who does it | Provider |
|-------|--------------|-------------|----------|
| S0 Intake | Validate, probe, make a working copy | Code | FFmpeg |
| S1 Signal scan | Candidate moments from motion (camera shake removed) and the clip's own sound; cuts ignored | Code | OpenCV, NumPy |
| S2 Analysis | Contact sheets of nine frames over the clip and around each candidate; the model answers with a cell; then a second independent look | Spotter, a fixed sequence of tool calls in the MVP | DeepSeek `deepseek-flash` |
| S3 Key moment | Narrower sheets until single frames, then snap to the sound onset | Spotter and code | DeepSeek `deepseek-flash` |
| Checkpoint A | You confirm or correct what happened | Human | none |
| S4 Storyboard | Lay out title, live, replay, end card | Code | none |
| S5 Script | Write the lines to timed slots, in the narrator's persona, checking each draft | Code and Commentator, a ReAct agent with a `check_lines` tool | DeepSeek `deepseek-flash` through agent-core |
| S6 Checks | Five code checks, run again on what the agent submitted | Code | none |
| S7 Voice | One take per line in the narrator's voice, fitted, and placed so each key line's key word lands on the moment | Code and provider | ElevenLabs `eleven_v3` (DeepSeek has no speech model) |
| S8 Sound | The narrator's sound set, mixed | Code | Library made once with ElevenLabs Sound Effects and Lyria 3 Clip |
| S9 Visuals | Slow motion, and graphics in the narrator's skin | Code | Real frames or FFmpeg blending; HTML templates |
| S10 Compose | Assemble and export | Code | FFmpeg |

A plain Python runner moves a run from stage to stage. DeepSeek takes still images, not video or sound, so the model sees contact sheets; the clip's sound is used only on the machine, by the signal scan.

Deferred from the full design: the Verifier agent, the Spotter's ReAct loop, the Nature documentary pack, re-telling from the page, half the code checks, fallback providers, frame interpolation, the score bug, captions, the 30-clip evaluation set (spec.md section 10).

## Narration styles

A style is a data file, not code. It sets the persona and writing rules, the voice, how each kind of line is delivered, the pace, the sounds and the graphics skin. It cannot change the facts, the picture or the checks. The MVP ships the first two; the Nature documentary pack is deferred (revision 7).

| | Football commentator | News reporter | Nature documentary |
|---|---|---|---|
| Sounds like | Excited play-by-play that treats any moment as the big match | A calm bulletin read with a straight face | A hushed, fond observer |
| Key line | Shouted at once | Stated as fact, a beat later | Almost whispered, later still |
| Around it | Crowd and roar | No crowd, bulletin stings | Outdoor ambience, quiet strings |

## People in the footage

- Adults only, and in the MVP men only. The person uploading confirms this, and has read a disclaimer: frames go to DeepSeek, in China, and may be used for training.
- Only still frames leave the machine, to one provider. Voice and script stages send text only. Slow motion is made on the machine.
- No stage says or guesses anyone's age, or remarks on anyone's body or ability.
- A clip showing someone hurt, upset, humiliated or undressed is not narrated.
- A run can be deleted at once from the page.

## Decided

- One person, nine working days; presentation on Saturday 24 October (revision 7).
- Any short moment, not only sport.
- Adults only (men only in the MVP); DeepSeek sees the frames, with a disclaimer (revision 6).
- The one-tap confirmation of what happened stays.
- The look of the page: dark, broadcast-style, coloured by the chosen narrator.
- Two narration styles in the MVP: Football commentator and News reporter (revision 7).
- The key moment: signal scan, contact sheets answered by cell, snap to the sound onset; the Spotter runs as a fixed sequence (revision 7).
- Voices from ElevenLabs `eleven_v3`; key lines placed by their key word (revision 7).
- ReAct agents on openJiuwen agent-core; a plain Python runner for the flow; no jiuwenswarm.
- DeepSeek as the model provider, using `deepseek-flash` (the successor to the retired `deepseek-v4-flash-vision-exp`).

## Still open

Nothing; see spec.md section 8.
