# Chinese commentary (spec revision 9): language toggle and Mandarin voices

Written 2026-10-30 (revised against the repo at 38ff345; corrected against `bd9731c` and the Z1 audition the same day, owner's review). Branch `zh`, cut from main at `bd9731c`.

## Where the build stands (read from the repo; verify)

- Everything in `tasks.md` is built except the timed run-through (T037). The three rehearsals (T034 second part) were run on 2026-10-30 at `880a73c`, ahead of plan. The presentation is **Wed 4 Nov**, code freeze **end of Sun 1 Nov**, Tue 3 is margin. The owner started this work on Fri 30; Sat 31 and Sun 1 have no planned build work left.
- So Chinese fits by Sun 1, but only as **a branch that must not endanger the English demo**.
- Already in place: `script.json` has a required `language` field (pattern `^[a-z]{2}$`; the Commentator writes `"en"` in one place); the speech adapter takes a `language` argument, which is in its recording key but is **not sent to ElevenLabs, and S7 never passes it**; the key line is placed by `hit_word` from ElevenLabs character timings (English checked live on `eleven_v3`), else by the take's first onset.

## What the owner decided

- A **language toggle on the home page**: English (default) / 中文 (Mandarin, Simplified), chosen at upload with the style, applying to the whole run.
- Mandarin voices on `eleven_v3`:

| Style | Voice (primary) | Voice id | Fallback (owner agreed 2026-10-30) | Voice id |
|---|---|---|---|---|
| Football commentator | DJ Allen (chosen 2026-10-31, replaced Karo Yang) | `lcRaSg5QsH61bfHjo8qM` | Yu (Taiwanese accent; the name 小宇 is **unconfirmed**, the library says only "Yu") | `fQj4gJSexpu8RDE2Ii5m` |
| News reporter | Jerry (chosen 2026-10-31, replaced Lao Dao) | `pz7hOfW7kgI9kF818eDS` | LiuPing (Beijing) | `pTOe8BQRdydOEIgv0wFL` |
| Nature documentary | Haoran | `pU9NaAwkoR3v0Mrg3uKz` | Jason Chen (Beijing) | `DowyQ68vDpgFYdWVGjc3` |

- All six are male, looked up with `/v1/shared-voices` (free). The Starter plan can use them; a shared voice synthesises without being added to the account. New ids go into `src/epic_replay/styles/voices.json` (the loader checks voices there with no network).
- Fallback voices are checked by the loader only. Nothing uses them at runtime: T030 is deferred, and a failed take gets one retry on the same voice.
- The library lists these voices as verified on `eleven_v4`, not `eleven_v3`, but the owner heard Karo Yang shout on v3 with `[excited] [shouts]` and not on v4. So v3 stays and no `zh` model field is needed.
- The owner found the calm football take perhaps too calm for football, so the `zh` football delivery is set livelier, and heard by ear in Z8.
- There is no sample player (T040 is deferred), so the sample question below falls away.
- **English must not change in any way**: no English prompt, request shape, cache key or pack field changes, so every existing recording, cached run and test replays with 0 new calls. **A golden-hash test proves it**: the English system prompts, briefs and speech request bodies and keys are hashed before the first code change, and the test fails if one byte moves.

## Rules for this session

1. Read `CLAUDE.md`, `docs/handoff.md`, `docs/provider-notes.md`, this file, and in `specs/001-epic-replay/` the spec, plan and tasks. Tell the owner what you found that differs from this file.
2. **Rule 1: the spec first.** Z2 writes revision 9 as a diff; the owner says yes before any code.
3. **Rule 4: any paid call** (Z1 audition, Z8 runs) gets its expected cost first and waits for the owner's yes.
4. **Rule 5 holds** (adults, men only, no age words, only frames to the vision endpoint). **Rule 9 holds**: a style is data and code never branches on a style id. Language is not a style, but keep language-specific code in as few places as possible (below).
5. The owner may not read Chinese: every Chinese text the owner must review (word lists, test lines, commentary) comes with an English gloss.
6. Work on a branch `zh` cut from main. **Merge only if the go/no-go below passes.** If it does not, leave the branch, say so, and the demo stays on main.

## Design (keeps the change small)

1. **Packs: add an optional `languages` block, do not migrate the packs.** The existing top-level fields stay the English definition. A pack may add `languages.zh` holding overrides for `script` (persona, rules, role_notes, example_lines, labels, banned_terms) and `voice` (primary, fallback, delivery, words_per_second, startup_s, reaction_delay_s), `script.labels.ai_label` is **required in a `zh` block: the loader refuses a `zh` block without it** (the AI label is a constant in `s10_compose.py` and `graphics/render.py` today, and the schema has no field for it; English keeps the constant). A resolver (next to `loader.for_run`) returns the pack as the run's language sees it; **every stage reads the resolved pack**, so stages need no language code. Update `style_pack.schema.json`; without a `zh` block the language is not offered for that pack.
2. **Spoken units.** The JSON fields `max_words` and `words_per_second` keep their names (no schema churn). `spoken_units(text, language)` gives one CJK character for `zh` (punctuation and spaces excluded); `words_per_second` is then characters per second for `zh`. **English has two counts today and keeps both:** S6 rule 5 uses the `SPOKEN` regex and the other sites use whitespace, and they differ on "3:2" or a lone "-". So each English path stays as written and only `zh` takes the new branch. `zh` call sites: `agents/commentator.py` (generic fallback, hit word at 67-70 and 201), `stages/s6_checks.py` (rule 5), `stages/s5_script.py:51`, `flow/runner.py:146` (the cost line: 6.5 characters a word would overstate `zh` about sixfold), `stages/s10_compose.py:59` (break between CJK characters, not at spaces), `providers/stubs.py`. `styles/slots.py` needs no change: it reads the rate from the resolved pack. Document the definition in `data-model.md`.
3. **Run setting `language`** (`en` | `zh`, default `en`): a form field on `POST /runs`, saved in the run record and returned by `GET /runs/{id}`, passed by the runner, with a `--language` option on `flow run`; the Commentator's hard-coded `"en"` (commentator.py:238) and the speech call use it. S7 passes `script["language"]`, and for `zh` only the request body carries `language_code: zh`. UI chrome, the play card and the progress text stay English; commentary, voice and the video's on-screen text switch.
4. **Commentator.** The prompt stays in English, and the English prompt stays byte-identical. One instruction is not enough, because it says "one word" and `max_words`: for `zh` those sentences have their own wording, and the `zh` instruction is to write the lines and the headline in Simplified Chinese (mainland usage), in the pack's `zh` persona. `GENERIC` is English, so `zh` has its own generic lines, reviewed by the owner with glosses (a Chinese voice must not read English in generic mode or in the fallback). Spell numbers with Chinese numerals, no digits (so counting and the number check stay simple). `hit_word` for `zh` is a substring of the line of one to three characters, not a whitespace token. **The regex in `s7_voice.word_start` never matches inside Chinese text** (a CJK neighbour counts as a word character), so it gets a `zh` path, with a test using the timings recorded in Z1. Timings for `zh` work on `eleven_v3` and `eleven_v4` (Z1 results below).
5. **Guards for `zh` (rule 5, FR-018).**
   - Lists of **multi-character** terms only, matched as substrings **after allowed phrases are masked out**: plain substring matching would fail 多少年 (contains 少年) and 空中超过 (contains 中超, "overtook in mid-air"). Single characters (女, 小, 老, 年) are too broad; the one exception is 她 (the female pronoun). False-positive tests are required: 多少年, 空中超过, 今年, 年度, 小心, 老师, 老板 must all pass. **Every list term is run against about 100 ordinary Chinese commentary sentences, shown with glosses for the owner's review.**
   - Seed terms for the owner to review (Claude Code completes the lists in the same categories as the English ones, with glosses): age: 孩子 child, 小孩 kid, 儿童 children, 少年 youth, 青少年 teenager, 年轻人 young person, 年幼 very young, 婴儿 baby, 宝宝 baby, 幼儿 toddler, 未成年 minor, 老人 old person, 老年 elderly, 年长 older; female: 女人 woman, 女孩 girl, 女士 lady, 女子 woman, 少女 young girl, 姑娘 young woman, 妈妈 mum, 母亲 mother, 她 she; real people, outlets, leagues (FR-018): 黄健翔, 宋世雄, 詹俊, 韩乔生, 刘建宏 (commentators), 赵忠祥 (narrator), 央视 / CCTV, 新闻联播, 新华社, 凤凰卫视, 英超 Premier League, 欧冠 Champions League, 中超, 世界杯 World Cup, 大卫·爱登堡 (Attenborough), 摩根·弗里曼 (Morgan Freeman).
   - The invented-name check cannot find arbitrary Chinese names. For `zh` it flags Latin-script words not in the corpus, and a short list of famous names (梅西, 罗纳尔多, C罗, 姆巴佩 ...); the Commentator prompt forbids names not given by the user. **State this limit plainly** (provider-notes, and the owner decides whether the deck mentions it).
   - The invented-number check reads Chinese numerals (二, 三 ... 十, 百, 千, 万, 两) as well as digits; digits in a `zh` line are a finding (item 4). 一 and 半 are not counted, as English does not count "one", and ordinary words are masked first: 一下 (a moment), 十分 (very), 千万 (must), 半空 (mid-air), 万一 (in case).
6. **Video text.** FFmpeg `drawtext` uses DejaVu Sans Bold (`s10_compose.py:36`), which has no Chinese glyphs, and the HTML overlays use Latin-only web fonts (`assets/fonts`). For `zh`: Noto Sans CJK SC Regular and Bold are installed, so no font is added. Index 0 of the `.ttc` files is Japanese, so the overlay needs `lang="zh-Hans"` and an SC font family, and the FFmpeg path (`FONT`, `fitted`) needs the SC face. The skins' italics and capitals are turned off for Chinese. Pack labels for `zh`: confirmed by the owner 2026-10-30: `ai_label` AI 解说; Football 回放 and 今日最佳; News 慢镜头 and 今晚头条; Nature 再慢慢看一次 and 在野外. The file's metadata tag stays as it is.
7. **Home page.** In `web/templates/_main.html`, inside the form, a two-option segmented control ("English | 中文", radio group named `language`, keyboard reachable, default English) beside the style cards; hide `中文` for a pack that has no `zh` block; `GET /styles` does not change, because a test holds its exact fields, and the template marks those packs. The choice is remembered per viewer in `localStorage` (try/catch; a convenience only). Follow `contracts/ui.md` and `design.md` (dark ground, wrapping flex rows, no media queries); update both, and `http-api.md`, in the same commit. The page stays `lang="en"`.
8. **English gloss, as a sidecar.** After S5 in a `zh` run, one cheap text call writes `gloss_en.json` (each line's English meaning) in the run folder, and the details view shows it if the details view can; it is review text only, never voiced, and it does not touch `script.json` or its schema. Cut this first if time is short.

## Steps (about 9 h; budget to be checked against the hours left)

| Step | Work | Est. | Paid |
|---|---|---|---|
| Z1 | Done 2026-10-30: voice IDs, 12 takes in `eval/spike/zh_voices/`, results below. **Go/no-go 1 passed by the owner's ear: the football voice shouts on v3 with tags.** | 1 h | 0.0153 USD |
| Z2 | Revision 9 as a diff (spec, plan, tasks, data-model, `style_pack.schema.json`, ui, design, http-api). Show it, wait for the yes. | 0.5 h | no |
| Z3 | First the golden-hash test, on unchanged code. Then the resolver, `languages.zh` blocks for the three packs, loader checks (voice in `voices.json`, labels present, `ai_label` required), `spoken_units`. English unchanged: run the full suite. | 1.5 h | no |
| Z4 | `language` end to end: form field, run record, runner, Commentator prompt, `zh` generic lines and `hit_word`, the S7 `zh` path with its test, speech call with `language_code`, the call sites in item 2, the sidecar gloss. | 2 h | no |
| Z5 | `zh` word lists with allowed phrases, false-positive tests, about 100 ordinary sentences with glosses, S6 rules for `zh`, planted-error script for a banned `zh` term. Owner reviews the lists and the sentences before they are committed. | 1.5 h | no |
| Z6 | CJK font in compose and in the HTML overlays, `zh` labels. | 1 h | no |
| Z7 | Page toggle, persistence, docs. | 1 h | no |
| Z8 | End to end in `zh` on FlippingPancakes and bottle flip (cost first; a live run through the page was about 0.07 USD for one clip). Owner listens and reads the glosses. If accepted: a cached `zh` run and backup video beside the English ones. | 1 h | yes |

Fit: Z1-Z4 on Sat 31, Z5-Z8 on Sun 1 morning. Do not touch the English cached runs or the deck.

## Go/no-go for merging into main (decided Sun 1 evening)

Merge only if all hold: the full suite passes with the English replay suite and cached runs unchanged (0 new calls), the golden-hash test included; `ruff` clean; a review round by a fresh reviewer is approved, as in earlier add-backs; the owner has listened to a `zh` result and accepts it; **one live English run through the page on the merge candidate (paid, cost first) and the replay suite both pass.** Otherwise: leave branch `zh`, main stays as frozen.

The rehearsals of 2026-10-30 were run at `880a73c`, so after a merge they describe code that no longer ships. **T037 runs after this decision, on the shipped code**, whichever it is.

## Done when

- A `language=zh` run through the page produces a finished video with a Mandarin voice, Chinese on-screen text and no banned term.
- A `language=en` run is byte-for-byte the same behaviour as before.
- The toggle works by keyboard and without `localStorage`.
- The owner has reviewed the `zh` word lists, the fallback voices and the first Chinese commentary with glosses.

## Z1 results (2026-10-30)

12 takes, all through `with-timestamps` with `language_code: zh`. Measured on the trimmed take. Script `tools/spike_z1_zh.py`, numbers `eval/spike/z1_zh.json`, notes in `docs/provider-notes.md`.

- Billing: 153 credits for 383 characters sent, 0.40 a character on both models, tags and punctuation counted (`character-cost` header and the account's count agree).
- Characters per second, punctuation excluded (calm line / long line): Karo Yang 2.78 / 2.62, Lao Dao 2.38 / 2.22, Haoran 4.29 / 3.04.
- The two-point fit gives a negative start-up on every voice, so these are not a start-up plus a rate. Pack values are set in Z3 from the slower figure.
- The six-character shout (`[excited] [shouts]` on v3) lasts 3.44 s, too long for the 2.0 s climax slot even at 12 percent faster, so the `zh` climax line must be about three characters.

## Voice change (2026-10-31)

The owner found that Karo Yang sounded a bit like a woman and auditioned DJ Allen and Xiaoming for football, then Jerry for news. Eight takes, 0.0117 USD, on `eleven_v3`. DJ Allen and Jerry replace Karo Yang and Lao Dao; the Z1 notes above describe the voices as they were. Pack rates are the slowest of the three lines, rounded down: football 2.8, news 3.1. Start-up values are unchanged and were not re-measured. Numbers in `docs/provider-notes.md` and `eval/spike/z1b_zh.json`.

## Open choices (ask the owner, do not decide alone)

- Whether the deck gets one added slide if `zh` ships.
- Cantonese is out of scope; the play card stays English.
