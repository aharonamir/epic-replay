# Research: providers for each stage of Epic Replay

Checked on 2026-10-11. Revision 2 added narration styles (sections 4, 5 and 11); revision 3 marked what the one-person MVP uses; revision 4 records the owner's choice of openJiuwen agent-core and DeepSeek (sections 2, 9, 10 and 11). Prices are list prices in USD and change often; re-check before committing a budget. Items I could not confirm are listed in section 13, and each has a spike task that settles it.

Stage numbers (S0 to S10) refer to plan.md section 4.

## 1. Recommendation at a glance

| Stage | What it needs | Primary | Fallback | Also worth a look |
|-------|---------------|---------|----------|-------------------|
| S0 Intake, S1 Signal scan | Probe, transcode, motion and audio peaks | FFmpeg, ffprobe, NumPy (local, free) | none needed | OpenCV optical flow, librosa onsets |
| S2 Analysis | Look at a 3 to 30 s clip and return structured events | **Chosen: DeepSeek `deepseek-flash`, looking at numbered frames** | Gemini 3.8 Flash, on frames or on video with sound | Qwen3-VL-Plus or Qwen3.5-Omni (API or self-hosted), TwelveLabs Pegasus |
| S3 Key moment | Pick the exact frame from up to 60 numbered stills | Same model as S2 | The second look, then the user's nudge at Checkpoint A | Any strong image model |
| S4 Storyboard | Template choice and arithmetic | Code | none needed | none |
| S5 Script | Short, timed, lively lines from a fact sheet | **Chosen: DeepSeek `deepseek-flash`** | `deepseek-v4-pro` | Claude Sonnet 5.5, Gemini 3.8 Flash, GPT-5.5 |
| S6 Verification | Independent read of script against facts | Code checks, then a model from a different family than the writer | Code checks alone | none |
| S7 Voice | Three narrators from one provider: a shout, an even read and a hushed read; short lines; stable voices | Gemini 3.8 Flash TTS or ElevenLabs Eleven v3, chosen by the bake-off | The other one | Hume Octave 2, Cartesia Sonic, Inworld, Speechify Simba, OpenAI gpt-4o-mini-tts |
| S8 Sound | A sound set per narration style: bed, key-moment sound, transition, stings | A curated library made once with ElevenLabs Sound Effects v2 and Lyria 3 Clip | Licensed stock sounds | ElevenLabs Music, Stable Audio 2.5, MMAudio V2 |
| S9 Slow motion | 0.25x to 0.5x of a 2 to 3 s window | Native high-frame-rate frames when present; RIFE otherwise (local, or fal.ai) | FFmpeg frame blending | Topaz Chronos on fal.ai (premium) |
| S9 Graphics | Score bug, lower third, REPLAY bug, end card | HTML and CSS rendered to transparent PNG with headless Chromium | Pillow-drawn PNGs | Remotion (licence check), Shotstack |
| S10 Compose | Cut, speed, overlay, mix, export | FFmpeg (local, free) | none needed | Remotion, Shotstack |
| Agents and flow | ReAct agents with tools; stages; two human checkpoints | **Chosen: openJiuwen agent-core `ReActAgent` for the two agents, a plain Python runner for the flow** | A hand-written ReAct loop over the same tools | jiuwenswarm Swarmflow, LangGraph |

Three ready-made combinations are in section 10 and the cost of each in section 11.

**What the one-person MVP uses.** Two keys. DeepSeek `deepseek-flash` runs both ReAct agents and looks at the frames. Gemini 3.8 Flash TTS speaks all three narrators, because DeepSeek has no speech model; ElevenLabs Eleven v3 comes in only if the day-2 test shows Gemini cannot shout. Slow motion comes from real frames or from FFmpeg on the machine. There is no Verifier model, no fallback provider and no hosted interpolation in the ten days. Children are likely in the footage, and DeepSeek's terms are the open question in section 2.

## 2. S2 and S3: understanding the play

This stage decides whether the product works. It needs a model that takes video with its audio, samples faster than one frame per second, and returns structured output.

### DeepSeek Flash (chosen by the owner)

- **The name.** `deepseek-v4-flash-vision-exp` was released on 2026-08-21 as an experimental model and retired on 2026-09-10, when DeepSeek-V4.1-Flash replaced it. The old name is "temporarily routed" to the new model. The name to use is `deepseek-flash`. The other current model, `deepseek-v4-pro`, does not take images.
- **What it accepts.** Still images only: JPEG, PNG, GIF, WebP, in the OpenAI-compatible chat format as `image_url` parts, inline as base64 or by URL. The documentation does not mention video or audio input. Up to 600 images in one request, a 48 MiB request body, and at most 1,024 tokens per image after resizing. A `detail` setting of `low` scales an image to 512 by 512 first. Images are allowed only in user messages.
- **What that means here.** The clip is cut into numbered frames. Twenty seconds at 4 frames a second is 80 images, well inside the limits. The model answers in frame numbers, so there is no whole-second limit as with Gemini's video input. It never hears the clip.
- **Price.** 0.30 USD per million input tokens and 1.20 per million output at peak; half that off-peak. Peak is 01:00 to 04:00 and 06:00 to 10:00 UTC on weekdays, which covers late morning in Israel. Tool calls and JSON output are supported.
- **Accuracy on sequences of frames** is not something I found measured. The model is described by DeepSeek as strong on multimodal agent benchmarks; whether it follows fast action across frames a quarter of a second apart is what T003 measures.
- **Open weights exist** (MIT licence, about 305 billion parameters), served with vLLM or SGLang. That is too large for most single servers, so it is not a practical route to keeping frames on your own machines.
- **Terms, which matter because children are likely in the footage.**
  - Privacy policy, updated 2026-02-10: personal data is used to "train and improve our technology"; there is a right to opt out by request to privacy@deepseek.com; data is collected, processed and stored in the People's Republic of China; the services "are not aimed at children".
  - The same policy says end-user data in apps built on the open platform is not covered by it. The Open Platform terms (effective 2026-04-29) are silent on training, retention and location, and require the developer to obtain end users' consent.
  - I found no commitment that API inputs are excluded from training. This does not meet FR-037 as written. spec.md section 8 sets out three ways to resolve it; the simplest is to keep the agents on DeepSeek and point the vision adapter, which is one line of configuration, at a provider with such a commitment.

### Gemini 3.8 Flash (the alternative for the frames, or for video with sound)

- Model id `gemini-3.8-flash`. 0.75 USD per million input tokens and 3.75 per million output tokens through 2026-12-31, then 1.50 and 7.50. There is a free tier, but free-tier content is used to improve Google's products and paid-tier content is not, so user footage must go through a paid key (FR-037).
- Takes video directly, including the audio track. Upload through the Files API; the documentation gives two different limits for inline video (under 100 MB in one place, under 20 MB total request in another), so do not use inline.
- Two processing modes. **Static** extracts frames at a fixed rate and is the documented choice for short clips and for "frame-level precision across the entire clip". **Agentic** lets the model navigate a long video and is aimed at long-form content. Use static.
- In static mode you can set `fps` and clip with `start_offset` and `end_offset`. These options exist only in static mode.
- Cost of video input: 258 tokens per frame at the higher resolution setting and 66 at the low setting, plus 32 tokens per second of audio. The media-resolution page gives 280 and 70 for the same thing; the estimates below use 280.
- Two limits that shape the design:
  - The default is 1 frame per second, and the documentation says fast action "might lose detail" at that rate. A goal can happen between two samples. We set a higher `fps`. The maximum allowed value is not stated on the page (section 13).
  - Timestamps are added once per second and referenced as `MM:SS`. The model cannot be relied on to report sub-second times. Stage S3 exists for this reason: we extract our own frames around the candidate moment, burn a frame number into each, send them as images, and ask for a frame number.
- Bounding boxes come back as `[y_min, x_min, y_max, x_max]` on a 0 to 1000 scale. The adapter converts them to the play sheet's 0 to 1 `x, y, w, h`.

### Gemini 3.1 Pro Preview (escalation)

- Model id `gemini-3.1-pro-preview`. 2.00 USD per million input and 12.00 per million output for prompts up to 200k tokens. No free tier.
- Use only for clips where the two Flash passes disagree, or if the spike shows Flash is not accurate enough. At this clip length the extra cost is a few cents.

### Qwen3-VL-Plus and Qwen3.5-Omni (second provider, and the private option)

- Available on Alibaba Cloud Model Studio. The API takes a video file or a list of frames with an `fps` parameter that controls frame extraction. Qwen3.5-Omni also takes the audio.
- Qwen3-VL has open weights, and its technical report claims video results on par with Gemini 2.5 Pro on standard benchmarks. It can be self-hosted (for example with vLLM) on your own GPU server. Children are expected in the footage, so this is the strongest privacy option: nothing leaves your machines. It is stretch item X8, costed at three to four hours if a server is already serving models, and the second thing to add back when there is time.
- Price: listings disagree by region. Artificial Analysis lists Qwen3.5 Omni Plus at 0.40 USD per million input and 4.80 per million output. Confirm in the console.

### TwelveLabs Pegasus 1.5 and 1.6

- Built for video, with emphasis on temporal grounding. Analyze is priced at 1.75 USD per hour of input video plus 7.50 per million output tokens; the free plan includes 600 minutes.
- Worth one test in the spike if Gemini's key-moment timing is poor. It adds a second vendor and an upload or indexing step.

### Take images only, like DeepSeek

- **OpenAI GPT-5.x**: published evaluations and a July 2026 survey describe the API as taking image frames, not video files (one paper reports a limit of 120 frames per request). Not re-checked this month.
- **Claude**: takes images, not video files (my understanding; not re-checked today). Both can serve S3, which uses stills, and S5 and S6.

### What the research says about accuracy

Sports papers from 2026 report that frontier multimodal models still struggle with fine-grained sports analysis, and benchmark setups differ so much in frame counts that scores are hard to compare. Plan for errors: two passes, a human checkpoint, and a generic mode. The spike (T003) measures real accuracy on your own clips before anything is built on it.

The clips no longer have to be sport. I expect slower everyday moments (a pet, a kitchen flip, a first bike ride) to be described more reliably than fast ball play, and to have a less sharply defined high point. Both are expectations, not measurements; the starter clips in T006 include both kinds so that T003 shows it.

### Spike protocol (T003)

Call the vision model directly, without an agent. For each starter clip that has no child in it: extract numbered frames; ask for the activity, the outcome and the frame of the high point from an overview at 2, 4 and 8 frames a second, at low and at full detail; then zoom on every frame one second either side of the answer. Record for each setting: outcome right or wrong, key-moment error in frames, latency, tokens and cost. Choose one overview rate and detail setting. If frames of clips with children will go to a different provider, run the same frames through it and compare.

## 3. S5 and S6: script and verification

- The script task is small: under 100 words, written to a fact sheet and a time budget. Any current frontier model can do it; the differences are in voice and in how well it keeps to the budget.
- **Writer**: in the one-person MVP, DeepSeek `deepseek-flash`, as chosen by the owner; the Commentator is a ReAct agent that checks its own draft with a tool before submitting. In the full design, decide by a blind read inside T016. Give two models the same five play sheets and pick the scripts you would rather hear. Candidates: Claude Sonnet 5.5 (`claude-sonnet-5-5`), Gemini 3.8 Flash (already in the stack, one key fewer), GPT-5.5.
- **Verifier**: use a different model family from the writer, so that the same blind spot is less likely to pass twice. If the writer is Claude, verify with Gemini, and the reverse.
- The verifier is the second line. The first is code (FR-019), which catches every name and number that is not in the play sheet.
- Cost: about 0.02 USD per run at Gemini 3.8 Flash prices, including two rewrite rounds.

## 4. S7: the commentator's voice

What matters here is not the usual ranking. The public arenas rank neutral naturalness for assistant-style speech. A commentator needs controlled shouting, fast pace, and the same voice across eight short separate takes. With three narration styles in the MVP, the provider also has to cover an even newsreader and a near-whisper, so range matters as much as peak excitement.

### Gemini 3.8 Flash TTS

- Model id `gemini-3.8-flash-tts`. 0.50 USD per million input text tokens and 9.00 per million output audio tokens through 2026-12-31 (doubling in 2027). Audio is 25 tokens per second, so about 0.002 USD per 10 seconds.
- Delivery is set per line with a short `style` string (for example "shouting, breathless, fast"), kept separate from the words. Inline tags give moments such as `<gasp>`, `<shout>`, `<cheer>`, `<short pause>`.
- 30 prebuilt voices, including ones described as Excitable, Upbeat, Lively and Gravelly, plus a larger library. **Voice design** creates a persistent custom voice from a text description, which is a clean way to get a house commentator that is no real person (FR-025).
- Over 130 languages, Hebrew among them (stretch item X3).
- Output is 24 kHz mono WAV. No word-level timing is documented; we do not need it because each line is a separate take placed by its start time.
- `gemini-3.8-flash-lite-tts` is cheaper (6.00 per million audio tokens) and uses the same request format.

### ElevenLabs Eleven v3

- Model id `eleven_v3`, generally available since February 2026. 0.10 USD per 1,000 characters, both direct and through fal.ai. Limit of 5,000 characters per request.
- Emotion and delivery through inline audio tags such as `[excited]`, `[whispers]`, `[laughs]`. Over 70 languages. A separate dialogue endpoint handles several speakers.
- Per-word timestamps are documented on fal.ai's endpoint.
- Widely regarded as the reference for expressive voices, and about seven times the price of Gemini TTS for this workload. At roughly 450 characters a run that is still under 0.10 USD.

### Others, if neither satisfies

| Provider | Why consider it | Price noted |
|----------|-----------------|-------------|
| Hume Octave 2 | Built for emotional delivery | about 7.60 per million characters |
| Cartesia Sonic 3.5 and 3.6 | Very low latency, high arena rank | usage based |
| Inworld Realtime TTS 1.5 Max | Top or near top of the arena in several snapshots | not checked |
| Speechify Simba 3.2 | First in the arena in July 2026 | 10 per million characters |
| OpenAI gpt-4o-mini-tts | Cheap, tone steerable by prompt, 11 built-in voices | about 0.015 per minute |
| Fish Audio S2 Pro, Kokoro | Open weights, for a fully local stack | free to run |

Gemini 3.8 Flash TTS is newer than the arena snapshots I found, which list Gemini 3.1 Flash TTS in the top three.

### Voices for the three narration styles

Each style needs its own voice on the primary provider and a stand-in on the fallback. Starting candidates among Gemini's prebuilt voices, by the one-word descriptions in Google's voice table; none of these has been listened to yet:

| Style | What the voice must do | Gemini prebuilt voices to try first | Delivery, per line |
|-------|------------------------|-------------------------------------|--------------------|
| Football commentator | Build, shout without distorting, come down | Fenrir (Excitable), Puck (Upbeat), Sadachbia (Lively) | "rising, quick"; "shouting, full voice" |
| News reporter | Even pace, clear consonants, no warmth swings | Charon (Informative), Rasalgethi (Informative), Schedar (Even), Kore (Firm) | "newsreader, even and clear" |
| Nature documentary | Low, close, unhurried, comfortable with pauses | Gacrux (Mature), Enceladus (Breathy), Achernar (Soft), Sulafat (Warm) | "hushed, unhurried" |

- **Gemini 3.8 Flash TTS** takes the delivery as a short style string per request, which maps directly onto the style pack's per-role delivery. Voice design can create each narrator from a description instead of using a prebuilt voice, which helps make three clearly different characters.
- **ElevenLabs Eleven v3** takes delivery as inline tags such as `[excited]` or `[whispers]`; the adapter converts the pack's delivery string into tags. Pick three library voices or design them; do not use a cloned voice (FR-025).
- A pack may name a different provider from the others. If the bake-off shows one provider shouts best and another whispers best, mix them; the cost is one more key.
- Pace differs by style and sets the word budget, so the speaking rate is measured per voice, not once.

### Bake-off protocol (T004)

One six-line script with a build-up line, a climax shout, and a calm replay line, plus one newsreader line and one hushed documentary line. Three providers, two voices each. Four listeners rank blind. Also record: spread of duration across five takes of the same line (we need predictable length), whether the voice stays the same across separate requests, latency per line, and whether the shout clips or distorts.

## 5. S8: crowd, effects and music

The MVP uses a small library made once and checked by a person (FR-027). This keeps runs free, fast and repeatable, and avoids a generated effect sounding wrong in a live demo. Each narration style has its own set: the football set is made in T021, the news and nature sets in T039.

- **ElevenLabs Sound Effects v2**: text to effect, 0.5 to 22 seconds through fal.ai (30 seconds maximum in ElevenLabs' own documentation), with a loop option for ambience. 40 credits per second when a duration is set. Use it to make: three crowd beds, three roars, two whooshes, a ball-hit accent.
- **Lyria 3 Clip** (`lyria-3-clip-preview`): 30 second clips, 44.1 kHz stereo, instrumental when asked in the prompt, about 0.04 USD per clip according to a third-party price listing dated 2026-09-11. Output carries an inaudible SynthID watermark. Use it for intro and outro stings.
- **ElevenLabs Music**: about 0.75 USD per minute through one reseller, with an instrumental-only switch.
- **Stable Audio 2.5** and **MMAudio V2** are listed by resellers; MMAudio generates sound from video, which could later produce a ball-hit effect in sync with the picture. Not evaluated.
- **Stock sounds** under a clear licence are an acceptable fallback. Record the licence of every asset in `assets/LICENCES.md` either way.

What each style's set contains:

| Style | Bed | Key moment | Transition | Stings |
|-------|-----|------------|------------|--------|
| Football commentator | Stadium crowd loop | Crowd roar; softer swell on the replay | Whoosh | Sport fanfare in and out |
| News reporter | None | None | Short broadcast swish | Bulletin opener and closer |
| Nature documentary | Meadow or park ambience loop | Quiet string swell | Soft swish | Gentle orchestral in and out |

The effects come from ElevenLabs Sound Effects v2 prompts; the stings and the string swell from Lyria 3 Clip, cut down from 30 second clips. One-time cost for all three sets: a few dollars.

## 6. S9: slow motion

The one-person MVP uses options 1 and 4 only. Options 2 and 3 are in the full design; the hosted versions send the clip to another vendor and should not be used for footage with children in it.

Order of preference:

1. **Real frames.** Many phones record at 120 or 240 fps. At 120 fps a 0.25x replay needs no invented frames at all. Intake reads the frame rate; the upload screen should say "film in slo-mo mode if you can". Phone slow-motion exports are sometimes already stretched or variable-rate, so T005 tests two phones.
2. **RIFE interpolation.** Open source. Run it locally, or call it: fal.ai `fal-ai/rife/video` at 0.0013 USD per compute second, or WaveSpeed at 0.01 USD per second of input.
3. **Topaz on fal.ai** (`topaz/interpolate/video`): about 0.30 USD per 10 seconds of 60 fps output at 1080p, slow-motion factor up to 8x, with a model (Chronos) described as natural slow motion for real-world movement. Premium switch, stretch item X6.
4. **FFmpeg frame blending** (`tblend` or `minterpolate`): free, always available, visibly softer. The last fallback.

Interpolation fails in a known way: fast small objects such as a ball, and crossing limbs, smear or double. T005 looks at the ball in each method's output, frame by frame.

## 7. S9: graphics

- **Default: HTML and CSS templates rendered to transparent PNG with headless Chromium (Playwright)**, then overlaid and faded by FFmpeg. Fast to design, no licence, and the same templates serve the web UI preview.
- **Remotion** produces animated graphics in React and renders through FFmpeg. It is free for individuals and organisations of up to three people; larger organisations need a company licence (25 USD per seat per month with a 100 USD monthly minimum, or 0.01 USD per render with the same minimum for automated pipelines). Check your organisation's position before adopting it.
- **Shotstack** is a hosted render API at roughly 0.20 to 0.25 USD per rendered minute. It removes local rendering but adds an upload of user footage to another vendor.

Graphics use the product's own name, colours and an openly licensed font. Do not copy the layout or colours of a real broadcaster's package.

## 8. S10: compose and export

FFmpeg does everything this product needs: cut, change speed, freeze, crop and zoom, overlay PNGs, mix and duck audio, loudness-normalise, burn captions, encode. It is free and deterministic, which matters for the timing test in SC-4. No hosted service is needed.

## 9. Agents and flow

**Chosen by the owner: openJiuwen agent-core for the ReAct agents.**

- Package `openjiuwen`, 0.1.19 released 2026-09-30, Apache-2.0, Python 3.11 or higher and below 3.14.
- `ReActAgent` runs a thinking, action, observation loop. It is built with an `AgentCard` and a `ReActAgentConfig` (`model_name`, `model_provider` defaulting to `"openai"`, `api_key`, `api_base`, `prompt_template`, `max_iterations` defaulting to 5), and offers `invoke()` and `stream()`, the latter yielding thoughts, tool calls and answers as chunks.
- Its model layer includes an `OpenAIModelClient` for OpenAI and compatible services. DeepSeek's API is OpenAI-compatible at `https://api.deepseek.com`, so that is the expected way in. DeepSeek is not named in the pages I read.
- I found no code sample for `ReActAgent` or for registering a tool; the published quick start builds a workflow agent. T002 establishes the real calls in two hours.
- **Images do not go through agent-core.** The agents reason in text and call tools; the tools send frames through our own vision adapter and return text. Whether agent-core can carry images in a message therefore does not matter.
- **Flow.** A plain Python runner with the stage interface in contracts/stage-contracts.md. agent-core also has a workflow engine with checkpoint recovery; it is not needed for a linear pipeline with one human step, and using it would add a second thing to learn in the ten days.
- **Fallback.** A hand-written ReAct loop of about 60 lines over the same tools, calling DeepSeek with the `openai` package. The tools hold the logic, so the swap is small.

Not used: jiuwenswarm Swarmflow (assumed in earlier revisions), LangGraph.

## 10. Stacks

| | A. One key (default) | B. Best voice | C. Private |
|---|---|---|---|
| S2, S3 analysis | Gemini 3.8 Flash | Gemini 3.8 Flash, 3.1 Pro on disagreement | Qwen3-VL self-hosted |
| S5 writer, S6 verifier | Gemini 3.8 Flash, Gemini 3.1 Pro | Claude Sonnet 5.5, Gemini 3.8 Flash | Self-hosted LLM for both |
| S7 voice | Gemini 3.8 Flash TTS | ElevenLabs Eleven v3 | Open-weights TTS |
| S8 sound | Library | Library | Library |
| S9 slow motion | Native or local RIFE | Native, RIFE on fal.ai, Topaz optional | Native or local RIFE |
| Keys needed | 1 | 3 | 0 |
| Footage leaves your machines | Yes, to Google | Yes, to Google and fal.ai | No |

**Chosen: stack D.** DeepSeek `deepseek-flash` for the Spotter, the Commentator and the frames; Gemini 3.8 Flash TTS for the voices; real frames or FFmpeg for slow motion; agent-core for the agents. Two keys. Frames leave your machines for DeepSeek, in China. **Stack D with the privacy fix** differs in one line of configuration: the vision adapter points at Gemini on the paid tier, and the agents stay on DeepSeek.

The table below shows the three stacks considered before that choice. Start the spikes with A. Move a single stage to B where the bake-off shows a clear gain. Stack A's verifier is from the same vendor as its writer, which is weaker than B on independence; the code checks carry more of the load there.

## 11. Cost per run

Assumptions: 20 second clip; two analysis passes at 5 fps with 280 tokens per frame plus audio; 40 stills for the refine step; 12k output tokens for analysis; 10k input and 4k output tokens for script and verification; 30 seconds of speech with every line generated twice; a 2.5 second replay window at 0.25x.

| Item | Stack A | Stack B | Stack B with Topaz |
|------|---------|---------|--------------------|
| Analysis (S2, S3) | 0.10 | 0.10 | 0.10 |
| Script and verification | 0.02 | 0.02 | 0.02 |
| Voice | 0.01 | 0.09 | 0.09 |
| Slow motion | 0.00 | 0.08 | 0.30 |
| Sound, graphics, render | 0.00 | 0.00 | 0.00 |
| **Total (USD)** | **0.13** | **0.29** | **0.51** |

- **Another style of the same play** repeats only the script and voice lines: about 0.04 on Stack A and about 0.11 on Stack B. The analysis, the storyboard and the slow motion are reused.
- A quieter, slower narrator speaks fewer words in the same time, so the voice line does not rise with the style.
- From 2027-01-01 the Gemini lines double: Stack A becomes about 0.27.
- **Stack D, the chosen one, costs about 0.10 USD a run at DeepSeek's peak price and about 0.06 off-peak.** The estimate: an overview of 80 frames, a second look at the same 80, and a zoom of 30 frames asked twice, all counted at the 1,024-token ceiling per image (about 225,000 image tokens); about 24,000 text tokens for the Spotter's loop and 16,000 for the Commentator's; 8,000 output tokens; and a cent for the voice. Low-detail frames should cost less than the ceiling, so this is an upper estimate. Another style of the same clip costs about 0.02.
- The fal.ai RIFE figure assumes 60 compute seconds; measure it in T005.
- The Stack B script line uses Gemini prices as a stand-in; recompute for the writer you choose.
- Formula, kept in `docs/provider-notes.md` and filled with measured values at Gate 0:
  `analysis_in_tokens x in_price + analysis_out_tokens x out_price + text_tokens x prices + speech_seconds x retake_factor x voice_price + interpolation`

## 12. Existing products

- **Announcer-voice generators** (VEED, FlexClip, Opus): the user types the commentary; the tool reads it in a sports-announcer voice.
- **Revid "AI Sports Commentary Generator"**: writes a script from a typed prompt and pairs it with generated generic visuals. Its page does not describe analysing uploaded footage.
- **Commentator AI** (iOS app): a simple commentary-voice app, noted in the earlier ranking.

None of the pages I read claims to describe the user's actual play. That is the gap this product fills, and it is also the hard part.

## 13. Not confirmed, and which task settles it

| Question | Settled by |
|----------|------------|
| Whether `deepseek-flash` follows fast action across numbered frames, and at what frame rate and detail setting | T003 |
| How many tokens a low-detail frame costs, and how long a request with 80 images takes | T003 |
| agent-core: the five questions in plan.md section 3 | T002 |
| Whether DeepSeek keeps or trains on API inputs; I found no statement either way for the API | Ask DeepSeek; decision in spec.md section 8 |
| Highest `fps` Gemini accepts in static mode (only if Gemini is used on video) | T003 |
| Whether sending numbered stills finds the exact frame reliably | T003 |
| Real accuracy on your own clips, per kind of clip (sport, trick, pet, family, everyday) | T003, then T028 |
| How long uploaded files stay on the Files API, and that delete works as expected (I believe they expire after 48 hours) | T033 |
| Whether OpenAI's API accepts video files as of this month | Not needed unless you want it as a candidate |
| Qwen prices on the international endpoint | T003 if Qwen is tested |
| Whether Gemini TTS keeps one voice stable across separate short requests, and how much take length varies | T004 |
| ElevenLabs v3 Hebrew quality; Gemini TTS Hebrew quality | X3 |
| Whether one provider can carry all three narrators, and which voices | T004 for the provider, T039 for the news and nature voices |
| Speaking rate of each chosen voice (the packs hold starting values of 2.6, 2.4 and 2.0 words per second) | T004, T039 |
| Lyria prices (taken from a third-party listing) | T021 |
| Latency of every paid stage | T003, T004, T005 |
| Whether DeepSeek has any speech model (none is listed on its pricing page) | Not needed; voices come from Gemini |
| Local RIFE install and speed on your machines | T005 (deferred) |
| Whether an open-weights video model on your own server matches the cloud model on your clips | X8 |

## 14. Sources

Video understanding
- [Gemini API: video understanding](https://ai.google.dev/gemini-api/docs/video-understanding)
- [Gemini API: media resolution](https://ai.google.dev/gemini-api/docs/media-resolution?hl=en)
- [Gemini Developer API pricing](https://ai.google.dev/gemini-api/docs/pricing)
- [Alibaba Cloud Model Studio: DashScope API reference](https://www.alibabacloud.com/help/model-studio/qwen-api-via-dashscope)
- [Qwen3-VL technical report](https://arxiv.org/pdf/2511.21631)
- [Qwen3.5 Omni Plus on Artificial Analysis](https://artificialanalysis.ai/models/qwen3-5-omni-plus)
- [TwelveLabs pricing](https://twelvelabs.io/pricing)
- [Vidi2.5 paper, evaluation setup for GPT-5 and Gemini](https://arxiv.org/pdf/2511.19529)
- [AnyGroundBench, grounding output formats by model family](https://arxiv.org/pdf/2607.02269)
- [TennisExpert, on limits of frontier models in sports analysis](https://arxiv.org/pdf/2603.13397)
- [AI video analysis overview, July 2026 (Japanese)](https://www.ai-souken.com/article/what-is-ai-video-analysis)

Voice
- [Gemini API: text-to-speech](https://ai.google.dev/gemini-api/docs/speech-generation)
- [Eleven v3 on fal.ai](https://fal.ai/models/fal-ai/elevenlabs/tts/eleven-v3)
- [ElevenLabs Eleven v3 API guide: pricing, limits, audio tags](https://aireiter.com/blog/elevenlabs-eleven-v3-api-guide)
- [Artificial Analysis speech arena leaderboard](https://artificialanalysis.ai/text-to-speech/leaderboard)
- [Arena snapshot scraped 2026-07-27](https://benchmarklist.com/arenas/artificial_analysis_provider_voice/)
- [Best text-to-speech providers 2026, FutureAGI](https://futureagi.com/blog/best-text-to-speech-providers-2026/)
- [Best AI text to speech tools 2026, Dupple](https://dupple.com/learn/best-ai-text-to-speech)
- [OpenAI TTS alternatives, Speechify, July 2026](https://speechify.ai/alternatives/openai)

Sound and music
- [ElevenLabs sound effects documentation](https://elevenlabs.io/docs/capabilities/sound-effects)
- [ElevenLabs Sound Effects v2 on fal.ai](https://fal.ai/models/fal-ai/elevenlabs/sound-effects/v2/llms.txt)
- [Gemini API: music generation with Lyria](https://ai.google.dev/gemini-api/docs/music-generation)
- [Lyria API pricing listing](https://benchlm.ai/md/media-pricing/lyria.md)
- [Venice music and sound-effect models](https://docs.venice.ai/de/models/music.md)

Slow motion and rendering
- [RIFE video interpolation on fal.ai](https://fal.ai/models/fal-ai/rife/video/llms.txt)
- [Topaz frame interpolation on fal.ai](https://fal.ai/models/topaz/interpolate/video)
- [RIFE on WaveSpeed](https://wavespeed.ai/zh-CN/models/wavespeed-ai/rife)
- [Remotion licence pricing](https://scriptagc.wasmer.app/https_remotion_pro/license)
- [FFmpeg vs Remotion](https://thegtmdirectory.com/compare/ffmpeg-vs-remotion)
- [JSON2Video alternatives 2026, including Shotstack pricing](https://motionbox.io/blog/best-json2video-alternatives-in-2026)

DeepSeek
- [DeepSeek API: models and pricing](https://api-docs.deepseek.com/quick_start/pricing)
- [DeepSeek API: vision guide](https://api-docs.deepseek.com/guides/vision/)
- [DeepSeek API: change log](https://api-docs.deepseek.com/updates/)
- [DeepSeek privacy policy](https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html)
- [DeepSeek Open Platform terms of service](https://cdn.deepseek.com/policies/en-US/deepseek-open-platform-terms-of-service.html)
- [DeepSeek-V4-Flash-Vision-Exp open weights](https://huggingface.co/deepseek-ai/DeepSeek-V4-Flash-Vision-Exp)
- [How to use the DeepSeek vision API, Apidog](https://apidog.com/blog/deepseek-v4-flash-vision-api/)

Agents and flow
- [openJiuwen agent-core repository](https://github.com/openJiuwen-ai/agent-core)
- [openjiuwen on PyPI](https://pypi.org/project/openjiuwen/)
- [agent-core wiki: ReActAgent](https://deepwiki.com/openJiuwen-ai/agent-core/2.1-reactagent)
- [agent-core wiki: model clients](https://deepwiki.com/openJiuwen-ai/agent-core/5.1-model-clients)
- [openJiuwen agent-core v0.1.17 release notes](https://github.com/openJiuwen-ai/agent-core/releases/tag/v0.1.17)
- [JiuwenSwarm README summary](https://gittrend.io/repo/openJiuwen-ai/jiuwenswarm)

Existing products
- [Revid AI sports commentary generator](https://www.revid.ai/tools/ai-sports-commentary-generator)
- [VEED sports announcer voice generator](https://www.veed.io/tools/voice-over-generator/sports-announcer-voice-generator)
- [Commentator AI: Sports Voice App](https://apps.apple.com/tt/app/commentator-ai-sports-voice/id6796303490)
