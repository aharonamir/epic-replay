# Quickstart: Epic Replay

The commands below are the targets that T001 and later tasks create. None of them exists until the corresponding task is done; the task is named beside each.

## 1. Prerequisites

- Python 3.11 to 3.13 (agent-core needs 3.11 or higher and below 3.14), FFmpeg 8 with ffprobe on the path, Git.
- A DeepSeek API key, for the two agents and for looking at frames. Use the model name `deepseek-flash`; `deepseek-v4-flash-vision-exp` is a retired name that is only routed to it for now.
- An ElevenLabs API key (Starter plan is enough; read permissions on voices, models and user) for the voices. DeepSeek has no speech model.
- Optional: an OpenAI key, the fallback speech provider.
- Only still frames from the clip leave the machine, and only to the vision endpoint in `.env`. Footage must show adults only; the page says that frames go to DeepSeek, in China, and may be used for training (spec.md section 8).

## 2. Set up (T001)

```bash
git clone <your repo> epic-replay && cd epic-replay
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"       # brings in openjiuwen (agent-core)
playwright install chromium
cp .env.example .env        # then add keys
pytest                      # everything runs in replay mode, no keys needed
```

`.env`:

```
EPIC_AGENT_PROVIDER=openai
EPIC_AGENT_API_BASE=https://api.deepseek.com
EPIC_AGENT_MODEL=deepseek-flash
EPIC_AGENT_API_KEY=
EPIC_VISION_API_BASE=https://api.deepseek.com
EPIC_VISION_MODEL=deepseek-flash
EPIC_VISION_API_KEY=
EPIC_VISION_SERVICE_NAME=DeepSeek
EPIC_VISION_COUNTRY=China
ELEVENLABS_API_KEY=         # voices (T004: ElevenLabs, eleven_v3)
EPIC_PROVIDER_MODE=replay   # live | record | replay
EPIC_CAP_RUN_USD=1.00
EPIC_CAP_DAY_USD=20.00
EPIC_CAP_PRE_CHECKPOINT_USD=0.30
EPIC_FRAME_FPS=4            # frames per second sent for analysis
EPIC_MOTION_MIN=0.0         # drop frames in still spans below this frame difference; 0 = off
EPIC_KEYFRAME_TOL_S=0.25
EPIC_MIN_OUTPUT_S=20
```

## 3. Run the fixture with no keys (T007, T027)

```bash
python -m epic_replay.flow run --fixture tests/fixtures/sample_run --mode replay
# writes runs/fixture-freekick/final.mp4, 28.9 s
```

This is the first thing to make work. It proves the storyboard, voice placement, mix and compose stages without spending anything.

## 4. Run on a real clip from the command line (T027)

```bash
EPIC_PROVIDER_MODE=live python -m epic_replay.flow run path/to/clip.mp4 \
    --style football_commentator --player-name "Noa"

python -m epic_replay.flow styles                       # list the style packs (T038)
python -m epic_replay.flow restyle <run_id> --style news_reporter   # same play, another narrator (T027, T039)
```

The command stops at the play card and prints it for a yes or no. Add `--no-express` to stop at the script as well.

## 5. Run the page (T029)

```bash
EPIC_PROVIDER_MODE=live uvicorn epic_replay.web.app:app --host 0.0.0.0 --port 8000
# open http://localhost:8000, or http://<this machine's address>:8000 from a phone on the same network
```

Everything happens on that one page: upload, choose the narrator, watch progress, confirm, watch and download, try another narrator (contracts/ui.md).

### Add a narration style (T038)

1. Copy a pack in `src/epic_replay/styles/` and give it a new `id`, `name` and `order`.
2. Edit the persona, rules and sample lines; choose a voice on both providers; measure its speaking rate with `python -m epic_replay.flow measure-voice <style_id>`.
3. Set the accent colour, and a lighter `accent_text` if the accent is too dark to read as small text on the page (contracts/design.md section 2). Name the sounds and the skin, adding files under `assets/` and `graphics/skins/` if they are new, with a line in `assets/LICENCES.md`.
4. Make the sample: `python tools/make_sound_library.py --sample <style_id>`.
5. Restart. The style appears in the picker if the pack validates; if not, the log says why.

## 6. Measure (T028, T036)

```bash
python eval/run_eval.py --set eval/labels --stages S0-S3      # SC-2, on the starter clips
pytest tests/planted_errors -q                                 # SC-3, the five kinds code can see
python -m epic_replay.media.timing_check runs/*/render.json    # SC-4
python -m epic_replay.guards.cost_report runs/                 # SC-5
pytest tests/unit/test_styles.py -q                            # every pack: valid, slots fit, no overlap
```

## 7. Demo mode (T034)

```bash
python -m epic_replay.flow freeze runs/<run_id> demo/freekick   # store a good run
uvicorn epic_replay.web.app:app --port 8000
# open http://localhost:8000/demo/freekick  - labelled "cached run"
```

## 8. Filming clips that work well

- Any moment with a high point works: a goal, a trick, a dog's catch, a pancake flip, a first ride. It does not have to be sport.
- Hold the phone still, or use a tripod. One continuous shot.
- Keep the whole action in frame, including where it ends: the goal, the pan, the dog's mouth.
- Use the phone's slo-mo mode (120 fps or more) if it has one. The replay then uses real frames.
- Three to thirty seconds. Start a few seconds before the action.
- Film only adults who are happy to be filmed (the MVP is for men). Nobody undressed.
- Still frames from the clip are sent to the vision provider named in `.env`; the page says which and where. `DELETE /runs/{id}`, or the link on the page, removes your local copy and its frames at once.

## 9. When something goes wrong

| Symptom | Look at |
|---------|---------|
| Run stops before the play card | `manifest.json` for the stage note; `cost.json` `blocked` list for a cap |
| Wrong outcome on the play card | `spotter_trace.json` for what the Spotter looked at and was told, and `second_look.json`; raise the overview frame rate or the detail setting |
| The Spotter loops or never submits | `spotter_trace.json`; the fixed-sequence fallback should have taken over, and the stage note says so |
| Agent calls fail at once | `docs/api-notes.md` from T002; the model name (`deepseek-flash`) and base URL in `.env` |
| Shout lands off the moment | `render.json` `climax_onsets`; then the climax frame in `play_sheet.json` |
| Lines overlap or are cut | `voice.json` for `speed_factor` and `dropped`; the measured words-per-second in the style pack |
| A style is missing from the picker | The start-up log: the pack failed validation, or names a voice or file that does not exist |
| A style sounds like the wrong narrator | The pack's `voice.delivery` strings first, then the voice itself; the persona only changes the words |
| Soft or ghosted replay | `visuals.json` `method`: `blend` means the clip was filmed at normal speed. Film in slo-mo for real frames |
| A test wants the network | A missing recording; re-run once with `EPIC_PROVIDER_MODE=record` |
