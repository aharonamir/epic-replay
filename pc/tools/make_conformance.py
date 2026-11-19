"""Write the shared conformance cases once, from today's Python code (spec 002, FR-020 to FR-022).

    python tools/make_conformance.py          # writes the cases that do not exist yet

Each case is `shared/conformance/<area>/<case>/case.json` (shared/conformance/README.md). The
expected output is what the Python code gives now; a small one is written inline, a larger one
to `expected/output.json`. A case whose folder exists is never rewritten, as the golden test
never rewrites its file: to change a case, delete its folder in the same commit as the spec
change that makes it differ. A case whose expected output is a shared fixture is checked
against that fixture instead of written.
"""

from __future__ import annotations

import json
import shutil
import sys

from epic_replay import conformance, paths
from epic_replay.guards import lexicon, lexicon_zh

INLINE_MAX = 400  # bytes of JSON; a larger expected output goes to expected/output.json
STYLES = ("football_commentator", "news_reporter", "nature_documentary")


def fixture(rel: str, **more) -> dict:
    return {"fixture": rel, **more}


def value(v) -> dict:
    return {"value": v}


def sample(name: str, **more) -> dict:
    return fixture(f"sample_run/{name}.json", **more)


def climax_only_at(t: float) -> dict:
    """The sample play sheet with the climax at `t` and only the climax event, at `t`."""
    ps = json.loads((paths.SAMPLE_RUN / "play_sheet.json").read_text())
    e6 = next(e for e in ps["events"] if e["id"] == "e6")
    return {"climax": {"t": t}, "events": [dict(e6, t_start=t, t_end=t)]}


def storyboard_cases() -> dict[str, dict]:
    def case(desc, ps, meta, expected=None, minimum=20.0):
        return {"area": "storyboard", "function": "storyboard.build", "language": None,
                "description": desc, "tolerance_s": 0.001,
                "inputs": {"play_sheet": ps, "clip_meta": meta, "min_output_s": value(minimum)},
                **({"expected": expected} if expected else {})}  # fmt: skip

    return {
        "sample_run": case(
            "The sample run's play sheet gives exactly the sample run's storyboard.",
            sample("play_sheet"), sample("clip_meta"), sample("storyboard"),
        ),
        "climax_at_start": case(
            "The climax on the clip's first frame.",
            sample("play_sheet", merge_patch=climax_only_at(0.0)), sample("clip_meta"),
        ),
        "climax_on_last_frame": case(
            "The climax on the clip's last frame (15 s): the replay holds its last frame.",
            sample("play_sheet", merge_patch={"climax": {"t": 15.0}}), sample("clip_meta"),
        ),
        "clip_3s": case(
            "A 3 s clip: the replay widens, then the end card holds to reach 20 s.",
            sample("play_sheet", merge_patch=climax_only_at(1.5)),
            sample("clip_meta", merge_patch={"duration_s": 3.0}),
        ),
        "clip_30s": case(
            "A 30 s clip: trimmed around the climax at 18 s.",
            sample("play_sheet", merge_patch=climax_only_at(18.0)),
            sample("clip_meta", merge_patch={"duration_s": 30.0}),
        ),
        "long_minimum_caps_the_replay": case(
            "A 40 s minimum: the replay widens to its 3.5 s cap and the end card holds for the rest (added in T414).",
            sample("play_sheet"), sample("clip_meta"), minimum=40.0,
        ),
        "no_focus_box_no_zoom": case(
            "A play sheet with no focus box: the replay has no zoom (added in T414).",
            sample("play_sheet", merge_patch={"focus_box": None}), sample("clip_meta"),
        ),
    }  # fmt: skip


def slots_cases() -> dict[str, dict]:
    out = {}
    for style in STYLES:
        for lang in ("en", "zh"):
            out[f"{style}_{lang}"] = {
                "area": "slots", "function": "slots.build", "language": lang,
                "description": f"The sample storyboard's slots in {style}, {lang}.",
                "tolerance_s": 0.001,
                "inputs": {"storyboard": sample("storyboard"),
                           "pack": {"style": style, "language": lang},
                           "climax_event_id": value("e6")},
            }  # fmt: skip
    out["climax_on_last_frame_football_en"] = {
        "area": "slots", "function": "slots.build", "language": "en",
        "description": "A climax on the last frame still leaves a full replay-climax slot.",
        "tolerance_s": 0.001,
        "inputs": {"storyboard": {"file": "../../storyboard/climax_on_last_frame/expected/output.json"},
                   "pack": {"style": "football_commentator", "language": "en"},
                   "climax_event_id": value("e6")},
    }  # fmt: skip
    return out


def checks_cases() -> dict[str, dict]:
    def case(desc, script, lang):
        return {"area": "checks", "function": "checks.check", "language": lang, "description": desc,
                "inputs": {"script": script, "play_sheet": sample("play_sheet"),
                           "user_context": sample("user_context"),
                           "pack": {"style": "football_commentator", "language": lang}}}  # fmt: skip

    out = {
        "clean_script": case("The sample run's script passes every check.", sample("script"), "en")
    }
    for name in ("invented_name", "invented_number", "banned_term", "missing_reference", "over_budget"):  # fmt: skip
        out[name] = case(f"Planted error: {name}, caught on its line.", fixture(f"planted_errors/{name}.json"), "en")  # fmt: skip
    out["zh_banned_term"] = case(
        "Planted error in Mandarin: a banned term, caught on its line.",
        fixture("planted_errors/zh_banned_term.json"), "zh",
    )  # fmt: skip
    return out


def lexicon_cases() -> dict[str, dict]:
    def scan(desc, lang, texts, extra=()):
        return {"area": "lexicon", "function": "lexicon.scan", "language": lang, "description": desc,
                "inputs": {"language": value(lang), "texts": value(list(texts)), "extra": value(list(extra))}}  # fmt: skip

    out = {
        "en_whole_word_false_positives": scan(
            "English terms match whole words only: none of these holds a banned word.", "en",
            ["Bold move, shifted the ball.", "He holds the line.", "The shepherds watch.",
             "A herald of the second half.", "Gold for the keeper.", "A kidney-shaped pitch.",
             "He shapes to shoot", "Short run-up, clean strike.", "TOP CORNER! What a strike!",
             "Shelter from the rain."],
        ),
        "en_found": scan(
            "English terms are found case-insensitively, across any whitespace, longest first.", "en",
            ["She shapes to shoot", "what a young talent", "Peter Drury would approve",
             "live on the BBC", "the little boy scores", "that fat keeper", "BREAKING NEWS tonight",
             "breaking\n  news", "she's there", "Match of the Day, the kid, the kids",
             "Old-fashioned? No: oldest trick, older than the wall."],
        ),
        "en_every_term": scan(
            "Every term of the global English list is caught inside a sentence.", "en",
            [f"Look at that, {t}, right there." for t in lexicon.GLOBAL_BANNED],
        ),
        "en_pack_terms": scan(
            "A pack's own banned terms are found after the global ones.", "en",
            ["a rare specimen", "the old specimen", "Specimens"], extra=["specimen"],
        ),
    }  # fmt: skip
    out["en_unicode_whitespace_and_case"] = scan(
        "Spaces in a term match any Unicode whitespace as Python reads it (no-break space, the "
        "ideographic space, the control separators), and case folding is Unicode ('ſ' is an s, "
        "the Kelvin sign a k); a zero-width space is not whitespace.", "en",
        ["breaking\u00a0news", "BREAKING\u3000NEWS", "match\x1cof\x1dthe\x1eday", "ſhe scores",
         "a \u212aid", "breaking\u200bnews", "breaking\u2028news", "match of\u0085the day"],
    )  # fmt: skip
    out["en_equal_length_alternatives"] = scan(
        "Two pack terms whose patterns have the same length are tried in code point order of the "
        "pattern text, so the text is read the same way on every run and platform.", "en",
        ["a   b ", "a   b", "a b  "], extra=["a b ", "a  b"],
    )  # fmt: skip
    for cat in json.loads((paths.LEXICONS / "zh.json").read_text("utf-8"))["banned"]:
        terms = list(getattr(lexicon_zh, cat.upper()))
        out[f"zh_every_{cat}_term"] = scan(
            f"Every Mandarin {cat} term is caught inside a sentence.",
            "zh",
            [f"看，{t}来了" for t in terms],
        )
    out["zh_she"] = scan("她 is the one single character banned; 她们 holds it.", "zh",
                         ["她来了", "她们来了", "他来了"])  # fmt: skip
    out["zh_allowed"] = scan(
        "Ordinary words that hold a banned term pass; a masked phrase does not hide a term beside it.",
        "zh",
        ["多少年了", "空中超过对手", "今年", "年度最佳", "小心", "老师", "老板", "不难看出",
         "太太太棒了", "他在仓库里", "紧张路段", "我靠近球门", "他的太太来了", "多少年了，孩子",
         "太太棒了", "防线瘫痪了", "肌肉记忆"],
    )  # fmt: skip
    out["zh_numerals"] = scan(
        "Chinese numerals are read as numbers; 一 alone, ordinals and fixed phrases are not.", "zh",
        ["十五分钟", "二〇二六年", "一千万", "两百零三", "十分精彩", "十分钟", "千万小心",
         "一千万人", "第三次", "一脚", "十足的把握", "三五个人", "他用了二十秒", "九十九", "一万零五百"],
    )  # fmt: skip
    out["zh_famous_names"] = scan(
        "A famous name or place is reported unless masked; it is not banned as such.", "zh",
        ["梅西进了", "皇马", "北京见", "温布利球场", "他进了"],
    )  # fmt: skip
    n = len(json.loads((paths.ZH_FIXTURES / "zh_sentences.json").read_text("utf-8")))
    for i, lo in enumerate(range(0, n, 25), 1):
        out[f"zh_sentences_{i}"] = {
            "area": "lexicon", "function": "checks.text", "language": "zh",
            "description": f"Ordinary Mandarin sentences {lo + 1} to {min(lo + 25, n)} of the "
                           "reviewed set pass every text check.",
            "inputs": {"language": value("zh"),
                       "texts": fixture("zh/zh_sentences.json", slice=[lo, min(lo + 25, n)], field="zh"),
                       "known": value(""), "extra": value([])},
        }  # fmt: skip
    return out


def units_cases() -> dict[str, dict]:
    def case(desc, lang, texts):
        return {"area": "units", "function": "units.spoken_units", "language": lang,
                "description": desc, "inputs": {"language": value(lang), "texts": value(texts)}}  # fmt: skip

    return {
        "en_words": case("English: one unit per whitespace token, punctuation included.", "en",
                         ["Here we go!", "  spaced   out  ", "", "3-2 up, 90 minutes", "TOP CORNER!",
                          "one\ttwo\nthree"]),
        "zh_characters": case("Mandarin: one unit per CJK character; punctuation, spaces, Latin "
                              "letters and digits count nothing.", "zh",
                              ["好球！", "他在 2026 年进球。", "Ａ队，好！", "“漂亮”——绝了", "", "看，这一脚！"]),
        "en_unicode_whitespace": case(
            "English: whitespace is what Python's split reads: no-break space, the ideographic "
            "space, the control separators and the line separators split; a zero-width space "
            "and a zero-width joiner do not.", "en",
            ["a\u00a0b", "a\u3000b\u3000c", "a\x1cb\x1dc\x1ed\x1fe", "a\u200bb", "a\u200db",
             "a\u2028b\u2029c", "a\u0085b", "\u00a0\u3000", "a\ufeffb"]),
        "zh_ranges": case(
            "Mandarin: the counted ranges are Extension A, the unified block and the compatibility "
            "block, and only those: the supplementary-plane ideographs, kana, hangul and fullwidth "
            "letters count nothing.", "zh",
            ["\u3400\u4dbf", "\u4e00\u9fff", "\uf900\ufaff", "\U00020000\U0002a6df", "あいう",
             "한국어", "\u9fff\ua000", "\u33ff\u3400", "\u4dc0\u4dff"]),
        "zh_text_read_as_en": case("Text read with the English rule counts tokens, whatever the script.",
                                   "en", ["好球！ 漂亮", "他在 2026 年进球。"]),
    }  # fmt: skip


def cell_layout_cases() -> dict[str, dict]:
    def case(desc, width, height, count):
        return {"area": "cells", "function": "cells.layout", "language": None, "description": desc,
                "inputs": {"width": value(width), "height": value(height), "count": value(count)}}  # fmt: skip

    return {
        "layout_landscape_1080p": case("A full sheet of 1920 by 1080 frames.", 1920, 1080, 9),
        "layout_portrait_1080p": case("Portrait frames make tall cells.", 1080, 1920, 9),
        "layout_odd_ratio": case("A ratio that does not divide: the cell height is rounded.", 333, 777, 9),
        "layout_half_even": case("A cell height that falls on .5 rounds to the even number, as Python's round does (2.5 is 2, not 3).",
                                 896, 5, 9),
        "layout_short_sheet": case("Five frames fill two rows.", 1280, 720, 5),
    }  # fmt: skip


def cells_cases() -> dict[str, dict]:
    def case(desc, start, end, fps, n, picks):
        return {"area": "cells", "function": "cells.narrow", "language": None, "description": desc,
                "inputs": {"start_s": value(start), "end_s": value(end), "fps": value(fps),
                           "n_frames": value(n), "picks": value(picks)}}  # fmt: skip

    return {
        "three_passes": case("Three sheets: the whole clip, then around cell 5, then around cell 6 "
                             "(cells are 1 to 9).", 0.3, 14.7, 30, 450, [5, 6]),
        "short_window": case("A window shorter than nine frames gives nine consecutive frames.",
                             5.0, 5.1, 30, 450, []),
        "window_at_clip_end": case("A window past the clip's end keeps nine frames inside the clip; "
                                   "picking cell 9 narrows at the end.", 14.9, 15.5, 30, 450, [9]),
        "clip_under_nine_frames": case("A clip of six frames gives every frame once.", 0.0, 1.0, 30, 6, [1]),
        "edge_cells": case("Cells 1 and 9 refine from the first and last cells.", 0.0, 10.0, 25, 300, [1, 9]),
    }  # fmt: skip


# -- spec 004, T408: the model client's requests, the recording key and the budget ----------


def _backend_scenarios() -> tuple[dict[str, list[dict]], list[dict]]:
    """The calls the golden test makes, caught at the client's door with their exact arguments
    (`backend_golden.json` pins what they send), and the arguments of every recording key."""
    import base64
    import sys
    import tempfile
    from pathlib import Path

    import pytest

    sys.path.insert(0, str(paths.PC / "tests" / "unit"))
    import test_backend_golden as g

    from epic_replay.providers import client, vision

    looks: list[dict] = []
    chats: list[dict] = []
    keys: list[dict] = []
    real_look, real_chat, real_key = client.look, client.chat, vision.cache_key

    def spy_look(ep, images, question, *, detail, schema):
        looks.append({
            "kind": "look", "detail": detail, "schema": schema, "question": question,
            "images": [{"label": lab, "base64": base64.b64encode(Path(p).read_bytes()).decode()} for lab, p in images],
        })  # fmt: skip
        return real_look(ep, images, question, detail=detail, schema=schema)

    def spy_chat(ep, messages, *, schema=None, tools=None):
        chats.append({"kind": "chat", "messages": messages, "schema": schema, "tools": tools})
        return real_chat(ep, messages, schema=schema, tools=tools)

    def spy_key(adapter, model, request, file_hashes=()):
        k = real_key(adapter, model, request, file_hashes)
        keys.append(
            {
                "adapter": adapter,
                "model": model,
                "request": request,
                "files": list(file_hashes),
                "key": k,
            }
        )
        return k

    golden = json.loads(g.GOLDEN.read_text())
    with pytest.MonkeyPatch.context() as mp, tempfile.TemporaryDirectory() as d:
        for n in ("EPIC_VISION_PROFILE", "EPIC_TEXT_PROFILE", "DEEPSEEK_API_KEY", "EPIC_VISION_API_BASE",
                  "EPIC_VISION_MODEL", "EPIC_AGENT_API_BASE", "EPIC_AGENT_MODEL", "EPIC_AGENT_PROVIDER"):  # fmt: skip
            mp.delenv(n, raising=False)
        mp.setenv("EPIC_VISION_API_KEY", g.KEY)
        mp.setenv("EPIC_AGENT_API_KEY", g.KEY)
        mp.setattr(client, "look", spy_look)
        mp.setattr(client, "chat", spy_chat)
        mp.setattr(vision, "cache_key", spy_key)
        g.vision_requests(Path(d) / "v", mp)
        g.text_requests(Path(d) / "t", mp)
    out, i = {}, 0
    for name in ("frames", "sheet", "sheet_no_schema", "events"):
        n = golden["vision"][f"{name}_requests"]
        out[f"vision_{name}"] = looks[i : i + n]
        i += n
    out["text_structured_draft"], out["text_gloss"] = chats[:1], chats[1:2]
    return out, keys


def backend_cases() -> dict[str, dict]:
    from epic_replay import conformance_backend

    scenarios, keys = _backend_scenarios()
    golden = json.loads((paths.PC / "tests" / "fixtures" / "backend_golden.json").read_text())
    out: dict[str, dict] = {}
    for name, calls in scenarios.items():
        role, scenario = name.split("_", 1)
        key = "test-key"
        got = conformance_backend.requests("deepseek-flash", role, key, calls)
        want = golden[role if role == "vision" else "text"][scenario]
        if got["sha256"] != want:  # the case must be what the golden file already pins
            raise SystemExit(f"backend/{name}: {got['sha256']} is not the golden {want}")
        out[f"requests_{name}"] = {
            "area": "backend", "function": "backend.requests", "language": None,
            "description": f"The DeepSeek requests of the {scenario.replace('_', ' ')} call ({role} role), "
                           "as pinned by pc/tests/fixtures/backend_golden.json.",
            "inputs": {"profile_id": value("deepseek-flash"), "role": value(role), "key": value(key), "calls": value(calls)},
        }  # fmt: skip
    tools = [{"type": "function", "function": {
        "name": "check_lines", "description": "Check a draft of lines. Returns each failure.",
        "parameters": {"type": "object", "properties": {"lines": {"type": "array", "items": {"type": "string"}}}, "required": ["lines"]},
    }}]  # fmt: skip
    out["requests_text_tools"] = {
        "area": "backend", "function": "backend.requests", "language": None,
        "description": "A chat call with a tool: the tool is sent in the OpenAI function format (spec 004, T408 review).",
        "inputs": {"profile_id": value("deepseek-flash"), "role": value("text"), "key": value("test-key"),
                   "calls": value([{"kind": "chat", "messages": [{"role": "system", "content": "You check lines."},
                                                                 {"role": "user", "content": "Check this draft: ['Here we go!']"}],
                                    "schema": None, "tools": tools}])},
    }  # fmt: skip
    seen = set()
    for n, k in enumerate(keys):
        if k["key"] in seen or n > 5:
            continue
        seen.add(k["key"])
        rec = golden["vision"]["recordings"]
        if f"vision/{k['key']}.json" not in rec:
            raise SystemExit("a recording key is not one of the golden recordings")
        out[f"recording_key_{len(seen)}"] = {
            "area": "backend", "function": "backend.recording_key", "language": None,
            "description": "A vision recording key of the golden test (the key of a recording the test stores).",
            "inputs": {"adapter": value(k["adapter"]), "model": value(k["model"]), "request": value(k["request"]), "files": value(k["files"])},
        }  # fmt: skip
    out["recording_key_non_ascii"] = {
        "area": "backend", "function": "backend.recording_key", "language": "zh",
        "description": "Non-ASCII text is written as it is in the canonical request (not as \\u escapes) before hashing.",
        "inputs": {"adapter": value("text"), "model": value("deepseek-flash"),
                   "request": value({"question": "好球！进球了", "n": 3, "t": 0.1, "ok": True, "none": None}), "files": value([])},
    }  # fmt: skip
    return out


def budget_cases() -> dict[str, dict]:
    caps = {"run_usd": 1.0, "day_usd": 20.0, "pre_checkpoint_usd": 0.3}

    def res(i, run, est, pre=False):
        return {"op": "reserve", "id": i, "run": run, "estimate": est, "pre_checkpoint": pre}

    def settle(i, actual):
        return {"op": "settle", "id": i, "actual": actual}

    def rel(i):
        return {"op": "release", "id": i}

    def case(desc, ops, **c):
        return {"area": "budget", "function": "budget.run", "language": None, "description": desc,
                "inputs": {"caps": value({**caps, **c}), "ops": value(ops)}}  # fmt: skip

    return {
        "settle_replaces_the_reservation": case(
            "A call reserves its estimate, then settles at its actual cost: the run total is the actual.",
            [res("a", "r1", 0.008), settle("a", 0.0035)],
        ),
        "run_cap_blocks_and_release_frees": case(
            "A second reservation that would pass the run cap is blocked; releasing the first lets it in.",
            [res("a", "r1", 0.6), res("b", "r1", 0.6), rel("a"), res("c", "r1", 0.6)],
        ),
        "in_flight_calls_count": case(
            "Calls in flight count against the cap before any has settled.",
            [res("a", "r1", 0.3), res("b", "r1", 0.3), settle("a", 0.1), res("b2", "r1", 0.3)], run_usd=0.5,
        ),
        "day_cap_is_shared_between_runs": case(
            "The day cap spans runs: a second run is blocked by what the first spent.",
            [res("a", "r1", 0.4), settle("a", 0.4), res("b", "r2", 0.2)], day_usd=0.5,
        ),
        "pre_checkpoint_cap": case(
            "The pre-checkpoint cap counts only calls marked so; others are not stopped by it.",
            [res("a", "r1", 0.2, True), res("b", "r1", 0.2, True), res("c", "r1", 0.2)],
        ),
        "billed_at_the_estimate": case(
            "A call that may still be billed (a timeout) is recorded at its estimate.",
            [res("a", "r1", 0.2), {"op": "settle_at_estimate", "id": "a"}, res("b", "r1", 0.9)],
        ),
        "a_recorded_reply_costs_nothing": case(
            "A recording answers at no cost: a reservation of 0 never blocks, even on a spent run.",
            [res("a", "r1", 0.99), settle("a", 0.99), res("b", "r1", 0.0), settle("b", 0.0)],
        ),
        "the_cap_itself_is_allowed": case(
            "Reaching a cap exactly is allowed; one cent over is blocked.",
            [res("a", "r1", 0.5), res("b", "r1", 0.5), res("c", "r1", 0.01)], run_usd=1.0,
        ),
    }  # fmt: skip


# -- spec 004, T411: the consent words and the rule that decides when consent is asked again ------


def consent_cases() -> dict[str, dict]:
    from epic_replay.guards import consent

    places = ("device", "local", "network", "cloud")
    out: dict[str, dict] = {}
    for v in places:
        for t in places:
            out[f"blocks_{v}_{t}"] = {
                "area": "consent", "function": "consent.blocks", "language": None,
                "description": f"The consent words when the frames go to {v} and the text to {t}.",
                "inputs": {"vision_location": value(v), "vision_label": value("Vision Model"),
                           "text_location": value(t), "text_label": value("Text Model")},
            }  # fmt: skip
    cur = consent.TEXT_VERSION
    checks = []
    for rv in places:
        for rt in places:
            for nv in places:
                for nt in places:
                    checks.append({"record": {"confirmed": True, "at": "2026-11-20T09:00:00Z", "text_version": cur,
                                              "vision_location": rv, "text_location": rt},
                                   "vision_location": nv, "text_location": nt})  # fmt: skip
    out["covers_every_pair_of_places"] = {
        "area": "consent", "function": "consent.covers", "language": None,
        "description": "A consent covers a run only if each role goes no further than it was given for: every recorded pair against every run pair.",
        "inputs": {"checks": value(checks)},
    }  # fmt: skip
    rec = {"confirmed": True, "at": "x", "text_version": cur}
    odd = [
        {"record": rec, "vision_location": "cloud", "text_location": "cloud"},  # no places named: the cloud wording
        {"record": rec, "vision_location": "device", "text_location": "network"},
        {"record": {**rec, "vision_location": "moon", "text_location": "cloud"}, "vision_location": "device", "text_location": "cloud"},  # an unknown place covers none
        {"record": {**rec, "text_version": "3", "vision_location": "cloud", "text_location": "cloud"}, "vision_location": "device", "text_location": "device"},
        {"record": {**rec, "confirmed": False, "vision_location": "cloud", "text_location": "cloud"}, "vision_location": "device", "text_location": "device"},
        {"record": {**rec, "vision_location": "local"}, "vision_location": "local", "text_location": "cloud"},  # no text place: the cloud
        {"record": {**rec, "text_location": "local"}, "vision_location": "cloud", "text_location": "local"},  # no vision place: the cloud
        {"record": {}, "vision_location": "device", "text_location": "device"},
    ]  # fmt: skip
    out["covers_odd_records"] = {
        "area": "consent", "function": "consent.covers", "language": None,
        "description": "Records with no places, an unknown place, an old wording, no confirmation, or one place missing.",
        "inputs": {"checks": value(odd)},
    }  # fmt: skip
    return out


def signals_cases() -> dict[str, dict]:
    """A tiny synthetic clip as a recipe (integers only, so every platform draws the same
    frames and samples). The scan's flow code differs between platforms (OpenCV here, Kotlin on
    the phone), so what is compared is when things are found, within 0.1 s (3 frames); weak
    peaks, which are noise in a clean clip, are left out by `min_score`."""
    base = {"width": 160, "height": 90, "fps": 30, "frames": 150}

    def ball(f, vx=8, vy=-3, n=3):
        return {"x": 20, "y": 60, "vx": vx, "vy": vy, "size": 10, "from": f, "to": f + n}

    def burst(t, amp=20000):
        return {"at_sample": round(t * 22050), "length": 600, "amp": amp}

    def audio(*bursts):
        return {"seconds": 5, "floor": 0, "bursts": list(bursts)}

    def case(desc, **recipe):
        return {
            "area": "signals", "function": "signals.scan", "language": None,
            "description": desc, "tolerance_s": 0.1,
            "inputs": {"recipe": value({**base, **recipe}), "top": value(3), "min_score": value(10)},
        }  # fmt: skip

    return {
        "strike_with_sound": case(
            "A ball is struck at 2.0 s and a thud sounds with it: one candidate there, with the sound onset just before it",
            objects=[ball(60)],
            audio=audio(burst(2.0)),
        ),
        "strike_without_sound": case(
            "The same clip with no sound track: motion only, no sound onsets", objects=[ball(60)]
        ),
        "sound_only": case(
            "A still picture and a loud thud at 2.0 s: the sound alone makes a candidate",
            audio=audio(burst(2.0)),
        ),
        "two_strikes": case(
            "Two strikes, 1.333 s and 3.333 s, with a louder and a quieter thud; two candidates in time order",
            objects=[ball(40), ball(100, vx=-8)],
            audio=audio(burst(40 / 30), burst(100 / 30, 12000)),
        ),
        "a_cut_is_not_an_event": case(
            "A strike at 2.0 s, then a hard cut to another picture at 3.5 s: the cut is listed and is no candidate",
            cut_frame=105,
            objects=[ball(60)],
            audio=audio(burst(2.0)),
        ),
        "a_camera_pan_is_not_motion": case(
            "The camera pans two pixels right and one down every frame; a strike at 2.0 s still stands out and the pan makes no candidate",
            pan=[2, 1],
            objects=[ball(60)],
            audio=audio(burst(2.0)),
        ),
        "loud_without_motion": case(
            "A strike with a thud at 2.0 s and a quieter thud at 3.5 s with nothing moving: two candidates, two loud peaks",
            objects=[ball(60)],
            audio=audio(burst(2.0), burst(3.5, 8000)),
        ),
    }


def checks_more_cases() -> dict[str, dict]:
    """Added in T415 for the Kotlin port: the text rules on many texts, and the script checks on edges."""

    def text(desc, lang, texts, known, extra=()):
        return {"area": "checks", "function": "checks.text", "language": lang, "description": desc,
                "inputs": {"language": value(lang), "texts": value(texts), "known": value(known),
                           "extra": value(list(extra))}}  # fmt: skip

    def script(desc, lang, patch, uc=None, pack="football_commentator"):
        base = "planted_errors/zh_banned_term.json" if lang == "zh" else "sample_run/script.json"
        return {"area": "checks", "function": "checks.check", "language": lang, "description": desc,
                "inputs": {"script": fixture(base, merge_patch=patch), "play_sheet": sample("play_sheet"),
                           "user_context": sample("user_context", **({"merge_patch": uc} if uc else {})),
                           "pack": {"style": pack, "language": lang}}}  # fmt: skip

    en_known = "noa free kick 7 seven top corner 2.5 goal"
    return {
        "text_en_names": text(
            "English names: capitals that start a sentence or are shouted pass unless listed; others must be known (rule 4).",
            "en",
            [
                "Free kick, just outside the box.",
                "TOP CORNER! What a strike!",
                "Then Noa shoots.",
                "Ronaldo scores",
                "RONALDO SCORES",
                "He said Hello there",
                "A header by Messi's side",
                '"Brazil" in the crowd',
                "(Wembley) roars",
                "Wow: Madrid",
                "Mid sentence Paris appears",
                "O'Neil takes it",
                "Noa's strike",
                "NOA'S strike",
                "Smith... Jones!",
                "one idea",
            ],
            en_known,
        ),
        "text_en_numbers": text(
            "English numbers in digits and words, in either form; one and zero are too common to count (rule 4).",
            "en",
            [
                "Seven goals in 3 games",
                "7.0 seconds",
                "2.50 metres",
                "1,000 fans",
                "10:30 kick off",
                "twenty yards",
                "thirty",
                "a hundred and ten",
                "zero chance, one idea",
                "٣ goals",
                "３ goals",
                "3 and 3.5 and 3,5",
                "two thousand",
            ],
            en_known,
        ),
        "text_en_banned": text(
            "English banned terms: the global list and a pack's own extra terms, as whole words (rule 8).",
            "en",
            [
                "She shoots",
                "that young talent",
                "the old man",
                "bold move",
                "what a specimen",
                "Specimen!",
                "SPECIMEN\u00a0shape",
            ],
            en_known,
            ["specimen"],
        ),
        "text_zh": text(
            "Mandarin lines: language, Simplified only, Latin words and famous names as invented names, digits, Chinese numerals, banned terms (rule 4 and 8).",
            "zh",
            [
                "进了！",
                "Goal 进了",
                "noa 进了",
                "NOA进了",
                "梅西进了",
                "进了3个",
                "进了３个",
                "进了٣个",
                "进了七个",
                "进了三十个",
                "进了一万个",
                "进了七个和一百",
                "後衛进了",
                "球隊进了",
                "没有汉字 only",
                "123",
                "她进了",
                "老人进了",
                "进了specimen",
                "进了 specimen",
            ],
            "noa 梅西 七 seven",
            ["specimen"],
        ),
        "text_zh_extra_terms": text(
            "A pack's own banned terms in Mandarin: an ASCII one is matched as a whole English word, a Chinese one as a substring (rule 8).",
            "zh",
            ["进了specimen", "进了 specimen 球", "进了点球", "点球 specimen", "进了球"],
            "",
            ["specimen", "点球"],
        ),
        "budget_float_max_words": script(
            "max_words written as a float: 8.0 equals the budget 8 and passes, 9.0 does not and the detail shows 9.0 (rule 5).",
            "en",
            {
                "lines": [
                    {
                        "id": "l1",
                        "segment_id": "s2",
                        "role": "opening",
                        "anchor": {"type": "start_at", "timeline_t": 1.3, "event_id": None},
                        "max_duration_s": 4.0,
                        "max_words": 8.0,
                        "text": "Free kick, just outside the box.",
                        "delivery": "x",
                        "event_refs": ["e1"],
                        "generic": False,
                    },
                    {
                        "id": "l2",
                        "segment_id": "s2",
                        "role": "build_up",
                        "anchor": {"type": "start_at", "timeline_t": 9.433, "event_id": None},
                        "max_duration_s": 1.6,
                        "max_words": 9.0,
                        "text": "Noa...",
                        "delivery": "x",
                        "event_refs": ["e3"],
                        "generic": False,
                    },
                ]
            },
        ),
        "headline_and_lower_third": script(
            "The headline and the lower third are read by rules 4 and 8 too.",
            "en",
            {"headline": "RONALDO SCORES", "lower_third": "Zidane saw a specimen of seven"},
        ),
        "wrong_rate_and_startup": script(
            "A script whose rate or start-up is not the pack's: the detail shows each value as Python writes it.",
            "en",
            {"words_per_second": 3, "startup_s": 0.90000001},
        ),
        "budget_edges": script(
            "max_words that the duration does not allow, a key line's floor of one word, a line over the smaller number.",
            "en",
            {
                "lines": [
                    {
                        "id": "l1",
                        "segment_id": "s2",
                        "role": "opening",
                        "anchor": {"type": "start_at", "timeline_t": 1.3, "event_id": None},
                        "max_duration_s": 4.0,
                        "max_words": 9,
                        "text": "one two three four five six seven eight nine ten",
                        "delivery": "x",
                        "event_refs": ["e1"],
                        "generic": False,
                    },
                    {
                        "id": "l2",
                        "segment_id": "s2",
                        "role": "climax",
                        "anchor": {"type": "hit_at", "timeline_t": 11.433, "event_id": "e6"},
                        "max_duration_s": 0.5,
                        "max_words": 1,
                        "text": "TOP CORNER!",
                        "hit_word": "TOP",
                        "delivery": "x",
                        "event_refs": ["e6"],
                        "generic": False,
                    },
                    {
                        "id": "l3",
                        "segment_id": "s2",
                        "role": "reaction",
                        "anchor": {"type": "start_at", "timeline_t": 13.0, "event_id": None},
                        "max_duration_s": 0.9,
                        "max_words": 0,
                        "text": "",
                        "delivery": "x",
                        "event_refs": [],
                        "generic": False,
                    },
                ]
            },
        ),
        "zh_typed_names_stay_as_typed": script(
            "Names the user typed are left as typed, Traditional forms and all; a Latin one is not an invented name only if known.",
            "zh",
            {"headline": "後衛进了", "lower_third": "Noa 好"},
            {"player_name": "後衛", "team_home": "Noa"},
        ),
        "zh_script_checks": script(
            "A Mandarin script with digits, a famous name and Traditional characters outside the typed names.",
            "zh",
            {"headline": "梅西进了3个", "lower_third": "球隊"},
        ),
    }


def voice_cases() -> dict[str, dict]:
    """Added in T416: where S7 puts each take, and the word timings it reads (stages/s7_voice.py)."""
    from epic_replay.providers.speech import tagged

    def aligned(text, delivery="", cut=None):
        sent = tagged(text, delivery)
        return {"chars": sent, "starts": [round(i * 0.05, 3) for i in range(len(sent))]}

    def call(text, word, language="en", delivery="measured, expectant", alignment="ok"):
        al = aligned(text, delivery) if alignment == "ok" else alignment
        return {
            "alignment": al,
            "delivery": delivery,
            "text": text,
            "word": word,
            "language": language,
        }

    def line(i, role, t, max_s, kind="start_at", text=""):
        return {"id": f"l{i}", "role": role, "max_duration_s": max_s,
                "anchor": {"type": kind, "timeline_t": t, "event_id": None}}  # fmt: skip

    def take(raw, lead=0.05, onset=None, word_at=None):
        return {"raw_duration_s": raw, "lead_s": lead, "onset_s": onset, "word_at_s": word_at}

    sample_lines = [
        line(1, "opening", 1.3, 4.0), line(2, "build_up", 9.433, 1.6), line(3, "climax", 11.433, 2.0, "hit_at"),
        line(4, "reaction", 13.683, 1.6), line(5, "replay_watch", 16.9, 3.5),
        line(6, "replay_climax", 22.4, 3.5, "hit_at"), line(7, "sign_off", 26.7, 1.6),
    ]  # fmt: skip

    def place(desc, lines, takes, delay=0.15, end=28.9):
        return {"area": "voice", "function": "voice.place", "language": None, "description": desc,
                "tolerance_s": 0.001,
                "inputs": {"lines": value(lines), "takes": value(takes), "delay": value(delay), "end": value(end)}}  # fmt: skip

    def words(desc, lang, calls):
        return {"area": "voice", "function": "voice.word_start", "language": lang, "description": desc,
                "tolerance_s": 0.0001, "inputs": {"calls": value(calls)}}  # fmt: skip

    fits = [
        take(2.9),
        take(0.9),
        take(1.1, onset=0.12),
        take(0.8),
        take(3.2),
        take(2.6, onset=0.2, word_at=0.62),
        take(1.1),
    ]
    return {
        "place_sample_run": place(
            "Every take fits: the key lines land on their markers by the first onset and by a word's timing.",
            sample_lines,
            fits,
        ),
        "place_a_long_take_is_sped_up": place(
            "A take 8 percent over its slot is played faster to fit; the key line keeps its place.",
            sample_lines,
            [take(3.0), take(1.7), *fits[2:]],
        ),
        "place_too_long_is_dropped_key_runs_over": place(
            "A take over 12 percent too long is dropped; a key line that is too long is kept and runs over.",
            sample_lines,
            [
                take(5.5),
                take(0.9),
                take(2.6, onset=0.12),
                take(0.8),
                take(3.2),
                take(4.6, onset=0.2, word_at=0.62),
                take(1.1),
            ],
        ),
        "place_line_before_the_key_line": place(
            "A line that would run into the key line is sped up to end 0.1 s before it, or dropped when that is not enough.",
            [
                line(1, "opening", 9.0, 4.0),
                line(2, "build_up", 9.9, 1.6),
                line(3, "climax", 11.433, 2.0, "hit_at"),
                line(4, "reaction", 13.683, 1.6),
            ],
            [take(2.6), take(1.55), take(1.1, onset=0.12), take(0.8)],
        ),
        "place_line_after_the_key_line": place(
            "A line that would start under the key line before it is dropped.",
            [
                line(1, "climax", 11.433, 2.0, "hit_at"),
                line(2, "reaction", 12.0, 1.6),
                line(3, "sign_off", 14.0, 1.6),
            ],
            [take(1.9, onset=0.1), take(1.0), take(1.0)],
        ),
        "place_past_the_end": place(
            "A line that would end after the video is dropped; a key line never is.",
            [line(1, "replay_climax", 22.4, 3.5, "hit_at"), line(2, "sign_off", 27.9, 1.6)],
            [take(2.0, onset=0.1, word_at=0.5), take(1.4)],
            end=28.9,
        ),
        "place_the_pad_is_cut": place(
            "A take that overshoots its slot by under the encoder's 0.02 s pad after the 12 percent is cut at the slot's end and kept.",
            [line(1, "opening", 1.3, 4.0), line(2, "sign_off", 26.7, 1.6)],
            [take(4.5), take(1.1)],
        ),
        "place_word_timing_at_the_edge": place(
            "A word timing exactly at the end of the trimmed take is outside it: the first onset is used.",
            [line(1, "climax", 11.433, 2.0, "hit_at")],
            [take(1.2, lead=0.3, onset=0.11, word_at=1.5)],
        ),
        "place_word_timing_at_the_start": place(
            "A word timing exactly where the trimmed take starts is inside it.",
            [line(1, "climax", 11.433, 2.0, "hit_at")],
            [take(1.2, lead=0.3, onset=0.11, word_at=0.3)],
        ),
        "place_word_timing_out_of_range": place(
            "A word timing outside the take (before the trimmed start, or past its end) falls back to the first onset.",
            [
                line(1, "climax", 11.433, 2.0, "hit_at"),
                line(2, "replay_climax", 22.4, 3.5, "hit_at"),
            ],
            [take(1.2, lead=0.3, onset=0.11, word_at=0.1), take(2.0, onset=0.2, word_at=2.5)],
        ),
        "word_start_english": words(
            "Where a word starts in the aligned take: whole words only, any case, the line's first word is left to the onset.",
            "en",
            [
                call("TOP CORNER! What a strike.", "CORNER"),
                call("TOP CORNER! What a strike.", "top"),
                call("Noa shoots, it is in.", "shoots"),
                call("He said she's in", "she"),
                call("He said she's in", "she's"),
                call("a bold move", "old"),
                call("What a strike", "STRIKE"),
                call("What a strike", "goal"),
                call("What a strike", ""),
                call("It's a goal. A goal!", "goal"),
                call("Half past o'clock now", "clock"),
                call('it\'s "goal" time', "goal"),
            ],
        ),
        "word_start_no_usable_timing": words(
            "No alignment, or one that is not over the text as sent, gives nothing.",
            "en",
            [
                call("What a strike", "strike", alignment=None),
                call("What a strike", "strike", alignment={"chars": "other", "starts": [0.0] * 5}),
                call(
                    "What a strike",
                    "strike",
                    alignment={
                        "chars": tagged("What a strike", "measured, expectant"),
                        "starts": [0.0, 0.1],
                    },
                ),
                call("What a strike", "strike", delivery=""),
                call("What a strike", "strike", delivery="shouting, full voice"),
            ],
        ),
        "word_start_with_emoji": words(
            "A character outside the Basic Multilingual Plane before the word counts as one position, in English and in Mandarin.",
            "en",
            [
                call("\U0001f525 TOP CORNER \U0001f525 What a strike", "strike"),
                call("What a \U0001f525\U0001f525 strike", "strike"),
            ],
        ),
        "word_start_mandarin_with_emoji": words(
            "The same in Mandarin: the position is counted in characters, not in UTF-16 units.",
            "zh",
            [call("\U0001f525进了，好球", "好球", "zh"), call("进了\U00020000好球", "好球", "zh")],
        ),
        "word_start_mandarin": words(
            "Mandarin has no spaces: the word is found as a substring, and the first character leaves the work to the onset.",
            "zh",
            [
                call("进了，好球", "好球", "zh"),
                call("进了，好球", "进了", "zh"),
                call("进了，好球", "没有", "zh"),
                call("好球好球", "好球", "zh"),
                call("太好了！", "好", "zh", delivery=""),
                call("Noa进了", "进了", "zh"),
            ],
        ),
    }


def _repeat_segments() -> list[dict]:
    """The sample storyboard's segments with a second live, transition and end card, each differing from the first."""
    segs = json.loads((paths.SAMPLE_RUN / "storyboard.json").read_text())["segments"]
    extra = [
        {**s, "id": s["id"] + "b", "timeline_start": round(s["timeline_start"] + 0.5, 3), "source": s["source"] and {"in_s": 1.0, "out_s": 2.0}}
        for s in segs if s["kind"] in ("live", "transition", "endcard")
    ]  # fmt: skip
    return [*segs, *extra]


def mix_cases() -> dict[str, dict]:
    """Added in T416: the layers S8 mixes, from the storyboard, the voice record and the pack (stages/s8_mix.py)."""
    sb = sample("storyboard")
    lines = [{"id": f"l{i}", "file": f"voice/l{i}.wav", "dropped": None, "timeline_start": t}
             for i, t in enumerate([1.3, 9.433, 11.12, 13.683, 16.9, 22.2, 26.7], 1)]  # fmt: skip
    voice = {
        "lines": [
            *lines[:3],
            dict(lines[3], dropped="would start under the key line before it"),
            *lines[4:],
        ]
    }

    def case(desc, style, lang="en", uc=None, meta=None, storyboard=None, expected=None):
        return {**({"expected": expected} if expected else {}), "area": "mix", "function": "mix.layers", "language": lang, "description": desc, "tolerance_s": 0.001,
                "inputs": {"storyboard": storyboard or sb, "voice": value(voice),
                           "pack": {"style": style, "language": lang},
                           "user_context": sample("user_context", **({"merge_patch": uc} if uc else {})),
                           "clip_meta": sample("clip_meta", **({"merge_patch": meta} if meta else {}))}}  # fmt: skip

    return {
        "layers_football": case(
            "Voice lines that were not dropped, the bed, one-shots at their markers and the clip's own sound under the live segment.",
            "football_commentator",
        ),
        "layers_nature_zh": case(
            "The same for another pack in Mandarin.", "nature_documentary", "zh"
        ),
        "layers_news": case("The same for the third pack.", "news_reporter"),
        "layers_original_audio_by_default": case(
            "A user context with no `keep_original_audio` keeps the clip's own sound.",
            "football_commentator",
            uc={"keep_original_audio": None},
            expected={"file": "../layers_football/expected/output.json"},
        ),
        "layers_repeated_segment_kinds": case(
            "Two segments of one kind: the mix reads the last of each (a dictionary keyed by kind), unlike the slots.",
            "football_commentator",
            storyboard={
                "fixture": "sample_run/storyboard.json",
                "merge_patch": {"segments": _repeat_segments()},
            },
        ),
        "layers_falsy_keep_original_audio": case(
            "keep_original_audio 0 is false as Python reads it: no original layer.",
            "football_commentator",
            uc={"keep_original_audio": 0},
            meta={"has_audio": 1},
            expected={"file": "../layers_original_audio_off/expected/output.json"},
        ),
        "layers_falsy_has_audio": case(
            "has_audio 0 is false as Python reads it: no original layer.",
            "football_commentator",
            uc={"keep_original_audio": 1},
            meta={"has_audio": 0},
            expected={"file": "../layers_original_audio_off/expected/output.json"},
        ),
        "layers_truthy_numbers": case(
            "has_audio 1 and keep_original_audio 1 are true: the original layer is there.",
            "football_commentator",
            uc={"keep_original_audio": 1},
            meta={"has_audio": 1},
            expected={"file": "../layers_football/expected/output.json"},
        ),
        "layers_original_audio_off": case(
            "The user turned the clip's own sound off: no original layer.",
            "football_commentator",
            uc={"keep_original_audio": False},
        ),
        "layers_clip_has_no_sound": case(
            "The clip has no sound: no original layer, the same layers as with the user's sound turned off.",
            "football_commentator",
            meta={"has_audio": False},
            expected={"file": "../layers_original_audio_off/expected/output.json"},
        ),
        "layers_without_a_replay_or_end_card": case(
            "A storyboard with no transition or end card: those one-shots are left out.",
            "football_commentator",
            storyboard={
                "fixture": "sample_run/storyboard.json",
                "merge_patch": {
                    "segments": [
                        s
                        for s in json.loads((paths.SAMPLE_RUN / "storyboard.json").read_text())[
                            "segments"
                        ]
                        if s["kind"] in ("title", "live", "replay")
                    ]
                },
            },
        ),
    }


def all_cases() -> dict[str, dict]:
    out = {}
    for make in (storyboard_cases, slots_cases, checks_cases, lexicon_cases, units_cases, cells_cases, cell_layout_cases, backend_cases, budget_cases, consent_cases, signals_cases, checks_more_cases, voice_cases, mix_cases):  # fmt: skip
        for name, case in make().items():
            out[f"{case['area']}/{name}"] = case
    return out


def write(rel: str, case: dict) -> str:
    final = paths.CONFORMANCE / rel
    if final.exists():
        return "kept"
    # Built beside its final place and renamed at the end, so a failure leaves no half case.
    d = final.with_name(f".{final.name}.partial")
    shutil.rmtree(d, ignore_errors=True)
    d.mkdir(parents=True)
    expected = case.pop("expected", None)
    case = {"area": case.pop("area"), "function": case.pop("function"), **case}
    (d / "case.json").write_text(json.dumps(case, ensure_ascii=False, indent=2) + "\n", "utf-8")
    got = conformance.run(d)
    if expected is not None:  # a shared fixture: check it, do not copy it
        want = conformance.resolve(expected, d)
        if conformance.compare(got, want, case.get("tolerance_s", 0.0)):
            raise SystemExit(f"{rel}: the code does not give {expected}")
    else:
        text = json.dumps(got, ensure_ascii=False)
        if len(text.encode()) <= INLINE_MAX:
            expected = value(got)
        else:
            (d / "expected").mkdir()
            out = d / "expected" / "output.json"
            out.write_text(json.dumps(got, ensure_ascii=False, indent=2) + "\n", "utf-8")
            expected = {"file": "expected/output.json"}
    case["expected"] = expected
    (d / "case.json").write_text(json.dumps(case, ensure_ascii=False, indent=2) + "\n", "utf-8")
    d.rename(final)
    return "written"


def main() -> int:
    seen = {"written": 0, "kept": 0}
    for rel, case in all_cases().items():  # storyboard first: a slots case reads its output
        what = write(rel, case)
        seen[what] += 1
        print(f"{what:8} {rel}")
    print(f"{seen['written']} written, {seen['kept']} kept (never rewritten)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
