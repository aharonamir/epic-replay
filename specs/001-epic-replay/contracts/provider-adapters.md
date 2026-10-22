# Models, agents and adapters

Revision 4. Three things talk to model providers: the two agent-core agents, the vision adapter and the speech adapter. No provider SDK is imported anywhere else (Principle V).

## Configuration

Everything that names a provider is in `.env` and read in one place, `providers/llm.py`:

```
# the two ReAct agents (text only)
EPIC_AGENT_PROVIDER=openai                 # agent-core client_provider for OpenAI-compatible services; confirm in T002
EPIC_AGENT_API_BASE=https://api.deepseek.com
EPIC_AGENT_MODEL=deepseek-flash
EPIC_AGENT_API_KEY=

# looking at frames
EPIC_VISION_API_BASE=https://api.deepseek.com
EPIC_VISION_MODEL=deepseek-flash
EPIC_VISION_API_KEY=
EPIC_VISION_SERVICE_NAME=DeepSeek          # shown in the consent line on the page
EPIC_VISION_COUNTRY=China                  # shown in the consent line on the page

# voices
EPIC_SPEECH_PROVIDER=openai       # openai | gemini; T004 chooses
GEMINI_API_KEY=
```

Pointing `EPIC_VISION_*` at another OpenAI-compatible vision endpoint moves the frames to that provider and changes the consent line with it. Nothing else changes.

## Agents

Built with openJiuwen agent-core's `ReActAgent`. The import paths and call signatures below the level of this description are established in T002 and written in `docs/api-notes.md`; do not guess them.

| Agent | Model | Max iterations | Tools | Ends when |
|-------|-------|----------------|-------|-----------|
| Spotter | `EPIC_AGENT_MODEL` | 8 | `clip_info`, `look_sheets`, `submit_play_sheet` (`refine` is deferred, revision 8) | `submit_play_sheet` accepts. In the MVP the Spotter runs as its fixed sequence, not as an agent (revision 7, plan.md section 4.3), overview only (revision 8) |
| Commentator | `EPIC_AGENT_MODEL` | 6 | `check_lines`, `submit_script` | `submit_script` accepts |

Rules for both:

- **Tools are plain Python functions** in `agents/<name>/tools.py` with typed arguments and a docstring. They hold the logic. The agent-core registration is a thin layer over them, so the same functions serve the hand-written loop if T002 chooses it, and the coded fallback.
- **Tools return text or small JSON, never images.** Frames go from a tool to the vision adapter and the description comes back as text.
- **Submit tools validate.** `submit_play_sheet` checks the schema, the descriptor rules and the limits; `submit_script` runs the code checks (five of the ten in the MVP) and checks that each key line's `hit_word` occurs in it. On failure they return the errors and the loop continues.
- **Limits live in the tools**, not in the prompt: frames per call, image calls per run, total iterations.
- **Every tool call is logged** to `spotter_trace.json` or `commentator_trace.json` and emitted as a stage event.
- **Coded fallback.** `agents/<name>/fallback.py` calls the same tools in a fixed order. The stage uses it when the agent does not submit in time.
- **Cost.** Each agent run is wrapped in the paid-call guard with an estimate from its iteration limit. Actual token usage is read from agent-core if T002 finds a way, and estimated from text length otherwise.

## Adapters

```python
from pathlib import Path
from typing import Protocol
from dataclasses import dataclass


@dataclass
class CallInfo:
    provider: str  # "deepseek", "google", "local", ...
    model: str
    cost_usd: float  # from the provider's usage report when it gives one
    latency_s: float
    cache_hit: bool


@dataclass
class Frame:
    path: Path  # JPEG with the frame number burned in
    number: int  # index into work.mp4
    t: float  # seconds from the start of work.mp4


@dataclass
class VisionResult:
    text: str  # the model's answer
    data: dict | None  # parsed and validated when a schema was given
    info: CallInfo


@dataclass
class AudioResult:
    wav_path: Path  # 24 kHz or higher, mono
    duration_s: float  # after trimming silence
    info: CallInfo


class VisionModel(Protocol):
    def estimate(self, n_frames: int, detail: str) -> float: ...
    def look(
        self, frames: list[Frame], question: str, *, detail: str = "low", schema: dict | None = None
    ) -> VisionResult:
        """Send the frames in order, each preceded by a text line 'Frame <number>, <t> s',
        inside the request (base64), never through a provider file store."""

    def look_sheet(
        self,
        sheet: Path,
        cells: list[dict],
        question: str,
        *,
        detail: str = "high",
        schema: dict | None = None,
    ) -> VisionResult:
        """One contact sheet of up to nine frames, cells numbered 1 to 9; `cells` is
        [{cell, frame, t}] and stays on this side (revision 7)."""


class SpeechSynth(Protocol):
    def estimate(self, text: str) -> float: ...
    def synthesise(
        self, text: str, *, voice_id: str, delivery: str, language: str = "en"
    ) -> AudioResult: ...
```

Rules for both adapters:

- **Paid-call wrapper.** Calls go through `guards.budget.paid_call(ctx, estimate, fn)`, which checks the caps before the call and writes estimate and actual to `cost.json` after it (FR-034). Calls may run in parallel: the check and a reservation of the estimate happen together under one lock, so calls in flight count against the caps, and the reservation is replaced by the actual cost when the call returns. Writes to `cost.json` and the day file take the same lock.
- **Record and replay.** `EPIC_PROVIDER_MODE` is `live`, `record` or `replay`. The cache key is a SHA-256 of adapter name, model, the canonical request and the hashes of any files sent. `record` reads the recording when there is one and calls the provider only when it is missing (replay first, so a re-run pays only for new requests). `replay` never touches the network and raises on a miss. Every reply is recorded, including one that does not fit its schema, so a replay repeats what happened.
- **Structured output.** With a schema, `look` and `look_sheet` ask for JSON and validate it. A reply that is not JSON or does not fit (a reply that repeats the schema is one) is asked once more with the line "Fill in the fields; do not repeat the schema.", as a new call with its own key; if that also fails they raise `SchemaMisfit` and the caller leaves the sheet out (plan.md 4.3). `CallInfo.attempts` is 2 when the retry was used.
- **One endpoint for pictures.** `VisionModel` is the only code that may be given a path under `frames/`, `sheets/`, `work.mp4` or the upload (FR-037). The automated test of this is deferred in revision 7.
- **Contact sheets (revision 7).** `look_sheet` sends one image with no text label before it. The `cells` list, which frame each numbered cell is, goes into the cache key and is never sent, so the model can answer only with a cell. Two costs (T003, measured): the cap check uses a ceiling of 0.008 USD a sheet call (`sheet_estimate`; the dearest call seen was 0.0071), and the cost shown to the user (play card, SC-5) uses the average of 0.0035 USD (`expected_sheets_usd`). A call already recorded reserves nothing. `look_sheet_set` sends several sheets in one call, each after the label "Grid k", with every grid's cells in the cache key; it is used for the events call (plan.md 4.4) and its estimate is one ceiling per sheet. Every vision call has a 45 s timeout (`TIMEOUT_S`); a timeout raises and the caller leaves the call out. A timed-out call may still be billed, so the ledger records it as spent at its estimate (`paid_call(..., billed_on=...)`); any other failure releases the reservation.
- **Speech mapping.** The speech adapter turns `delivery` into whatever the provider uses: a style field for Gemini TTS, inline tags for ElevenLabs. Each style pack names its voice.

## What is and is not replayed in tests

- Vision and speech calls are recorded and replayed, so the coded fallbacks and every stage after the play sheet run in tests without a network.
- The agents' own model calls go through agent-core's client and are not recorded. Tests cover the tools, the validators and the fallbacks. The agents themselves are exercised live in T013, T016 and T031.
- Demo mode needs no model at all: it replays stored stage outputs.

## In the full design

A fallback for each interface (a second vision provider as escalation, a second speech provider with a voice per style), a `FrameInterpolator` adapter for RIFE, and a Verifier agent. See plan.md section 4.15.

Sound effects and music have no runtime adapter: the library is built once by a script in `tools/make_sound_library.py` and checked in (FR-027). The same script makes the five-second sample clip for each style pack.
