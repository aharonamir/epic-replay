# Epic Replay

Film it. We'll call it.

Epic Replay turns a short clip into a narrated highlight video. Upload a moment worth replaying, such as a goal, a trick or a pancake flip, and choose who narrates it. The app finds the key moment, asks you to confirm what happened, writes the commentary, records the voice, builds a slow-motion replay, mixes the sound and hands you a finished video.

The clip can be any moment, not only sport. Every video carries a visible "AI commentary" label.

## How a run works

1. **Upload** a clip of 3 to 30 seconds (MP4, MOV or WebM, up to 200 MB).
2. **Choose a narrator** and a language, and tick the consent box.
3. **Wait** while the app checks the clip, looks at it and finds the key moment.
4. **Confirm the play card**: pick the moment, then keep or remove what the app says happened. This is the step that stops a wrong claim from reaching the script.
5. **Wait** while it writes the call, builds the replay, records the voice and mixes.
6. **Watch and download** the result. You can also read the script and see the time and cost of each step.
7. **Try another narrator** if you like. It returns to the start page with the same clip, so you only choose a narrator and language. There is no upload and no second confirmation, and it costs only the new script and voice.

### Narrators

| Narrator | Sounds like | Key line |
|----------|-------------|----------|
| Football commentator (default) | Excited play-by-play that treats any moment as the big match | Shouted at once |
| News reporter | A calm bulletin read with a straight face | Stated as fact, a beat later |
| Nature documentary | A hushed, fond observer | Almost whispered, later still |

All three speak English or Mandarin (Simplified Chinese). The page itself stays in English.

A narrator is a data file in `shared/styles/`, not code. It sets the persona, writing rules, voice, pace, sounds and graphics.

## Set up

You need:

- Python 3.11 to 3.13
- FFmpeg 8 with ffprobe on the path
- [uv](https://docs.astral.sh/uv/)
- A local checkout of openJiuwen agent-core beside this repository (`../agent-core` from the repository root)
- A DeepSeek API key and an ElevenLabs API key (paid tiers)

The Python app lives in `pc/`; every command below runs there. `pip install -e ".[dev]"` does not resolve, because openjiuwen depends on a package by git URL. Install in this order instead (the CI job does the same on a clean machine):

```bash
cd pc
uv venv --python 3.12 && source .venv/bin/activate
uv pip install -r requirements-ci.txt          # pinned: the project's and agent-core's dependencies
uv pip install -e ../../agent-core --no-deps
uv pip install -e ".[dev]" --no-deps
playwright install chromium
cp .env.example .env
```

Then fill in `pc/.env`. Never commit it. These are the settings that matter most:

| Setting | Meaning |
|---------|---------|
| `DEEPSEEK_API_KEY` | Used for the agents and for looking at frames, unless `EPIC_AGENT_API_KEY` or `EPIC_VISION_API_KEY` is set |
| `ELEVENLABS_API_KEY` | The voices |
| `EPIC_AGENT_MODEL`, `EPIC_VISION_MODEL` | `deepseek-flash` |
| `EPIC_PROVIDER_MODE` | `live`, `record` or `replay`; see below |
| `EPIC_CAP_RUN_USD` | Most one run may spend, default 1.00 |
| `EPIC_CAP_DAY_USD` | Most one day may spend, default 20.00 |
| `EPIC_CAP_PRE_CHECKPOINT_USD` | Most a run may spend before the play card, default 0.30 |

`.env.example` lists the rest.

## Run the app

```bash
cd pc && source .venv/bin/activate
EPIC_PROVIDER_MODE=record uvicorn epic_replay.web.app:app --port 8010
```

Open <http://localhost:8010>. Keep it on localhost: the app has no login, and the past replays list shows every video to anyone who can reach it.

### Provider modes

The mode decides whether a run spends money. Changing it means restarting the server. If it is not set, the app uses `replay`.

| Mode | What it does | Use it for |
|------|--------------|------------|
| `record` | Reuses a saved provider reply when there is one, and pays for and saves the rest | Normal use and demos |
| `live` | Always calls the providers and saves nothing | Checking the real path |
| `replay` | Uses saved replies only and never pays | Tests and already recorded clips |

In `replay`, a clip with no saved replies stops at "Writing the call" with "no recording for commentator".

### Cost

A new clip costs about 0.05 USD. The last fully live run cost 0.047 USD. A new narrator or language on a clip you have already run costs about 0.02 to 0.04 USD, and an exact repeat in `record` mode costs nothing.

A paid call that would pass a cap is not made, and the run stops with a message.

### Finding past videos

Finished videos stay in `pc/runs/` until you delete them. The **Past replays** link at the top of the home page lists them, newest first. Open one to play it, switch to the **Original clip** tab to see the clip before any commentary, download the replay or press **Delete this clip now**, which removes the clip and every version made from it, for all narrators, from this machine.

### Cached demo runs

`/demo/<name>` shows a stored run from the `pc/demo/` folder (`EPIC_DEMO_DIR` moves it), for example `/demo/flipping_pancakes_football`, marked as a cached run. It makes no provider call, so it is the fallback if a live run fails. `pc/demo/` and `pc/runs/` are not in git, so a fresh clone has neither.

## Command line

```bash
python -m epic_replay.flow styles
python -m epic_replay.flow run clip.mp4 --consent --style news_reporter --language zh --mode record
python -m epic_replay.flow run --fixture ../shared/fixtures/sample_run --mode replay
python -m epic_replay.flow rerun RUN_ID --from S5 --mode record
python -m epic_replay.flow restyle RUN_ID --style nature_documentary --mode record
```

`run` asks you to pick the key moment at the prompt; `--pick proposed` answers without asking. `rerun` deletes the outputs of that stage and the ones after it, then runs them again. `restyle` tells the same play with another narrator.

## How it works

A plain Python runner moves each run through these stages. Each run is a folder in `pc/runs/` holding every stage's output.

| Stage | What happens | Done by |
|-------|--------------|---------|
| S0 Intake | Validate the clip and make a working copy | Code, FFmpeg |
| S1 Signal scan | Find candidate moments from motion and the clip's sound | Code |
| S2 Analysis | Look at contact sheets of frames; the model answers with a cell | Spotter, DeepSeek |
| S3 Key moment | Narrow down to one frame and snap it to the sound | Spotter and code |
| Checkpoint A | You confirm or correct what happened | You |
| S4 Storyboard | Lay out title, live play, replay and end card | Code |
| S9 Visuals | Build the slow motion | Code, FFmpeg |
| S5 Script | Write timed lines in the narrator's persona | Commentator, a ReAct agent on agent-core with DeepSeek |
| S6 Checks | Check references, names, numbers, word budget, banned terms and language | Code |
| S7 Voice | Record each line and place key lines on the moment | ElevenLabs `eleven_v3` |
| S8 Sound | Mix the voice, the clip's sound and the narrator's sound set | Code |
| S10 Compose | Add the graphics and export the video | Code, FFmpeg, Chromium |

Everything code can check or produce is done by code. Agents choose what to look at and what to write. If the Commentator fails, a coded fallback finishes the script and marks the stage `degraded`, so the run still gives you a video.

## People in the footage

- Adults only, and men only in this version. The person uploading confirms this at the consent box.
- Do not send any clip with a child in it.
- Only still frames leave the machine, and only to the vision endpoint in `.env`. That provider may use what it receives to train its models.
- Scripts go to the text and voice providers as text. Video and sound stay on the machine.
- No line states or guesses anyone's age or remarks on a body.
- A clip showing someone hurt, upset, humiliated or undressed is not narrated.

## Limits of this version

- Versions of a clip are separate results, with no tabs between them.
- The narrator cards have no voice samples, and there is no "Keep for the demo" option.
- Local runs are not purged automatically; delete them yourself.
- There is no automated reader check of the script, and only some of the planned code checks are built.
- Slow motion uses frame blending, not frame interpolation.
- The invented-name check cannot find an arbitrary name written in Chinese.
- There is no second voice or text provider to fall back on.

`specs/001-epic-replay/spec.md`, section 10, has the full list.

## Develop

The repository holds two apps that read one set of data:

| Folder | Contents |
|--------|----------|
| `shared/` | Data both apps read, never code: JSON schemas, narrator packs and voices, banned-word lists, sounds and fonts, reference fixtures, and the conformance cases a port must pass. [shared/README.md](shared/README.md) |
| `pc/` | The Python app described here. [pc/CLAUDE.md](pc/CLAUDE.md) |
| `android/` | The Android app, not started; specified in `specs/004-…` |
| `specs/` | The designs, the source of truth: `001-epic-replay` (the app), `002-platform-split` (this layout), `003-model-backends` |
| `eval/labels/` | Labels of the evaluation clips, for both apps; the clips stay local |
| `docs/` | API, provider and evaluation notes, the Mandarin plan; `docs/archive/` holds superseded notes |

From `pc/`:

```bash
pytest                                    # replay mode, no keys or network needed
pytest -m live                            # real provider calls, costs money
ruff check . && ruff format --check .
```

| Path | Contents |
|------|----------|
| `pc/src/epic_replay/paths.py` | Where every folder is; the one module that finds the repository |
| `pc/src/epic_replay/flow/` | The runner and command line |
| `pc/src/epic_replay/stages/` | One module per stage |
| `pc/src/epic_replay/agents/` | The Spotter and Commentator |
| `pc/src/epic_replay/styles/` | The pack loader and the slot builder |
| `pc/src/epic_replay/providers/` | Vision, speech and the record and replay layer |
| `pc/src/epic_replay/guards/` | Spending caps, consent and banned terms |
| `pc/src/epic_replay/web/` | The page: API, templates, style and script |
| `pc/tests/` | Unit tests, recorded provider replies and the English golden file |
| `shared/styles/`, `shared/lexicons/` | Narrator packs and voices; banned-word lists |
| `shared/conformance/` | Cases every platform must pass; `pc/tests/unit/test_conformance.py` runs them |

Start with these documents:

- [spec.md](specs/001-epic-replay/spec.md): what the product does and what this build defers
- [plan.md](specs/001-epic-replay/plan.md): the stages and how they fit together
- [contracts/ui.md](specs/001-epic-replay/contracts/ui.md) and [contracts/design.md](specs/001-epic-replay/contracts/design.md): the page
- [contracts/http-api.md](specs/001-epic-replay/contracts/http-api.md): the API
- [docs/chinese-language.md](docs/chinese-language.md): Mandarin
- [CLAUDE.md](CLAUDE.md) and [pc/CLAUDE.md](pc/CLAUDE.md): working rules for this repository and for the Python app
