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


def visuals_cases() -> dict[str, dict]:
    """Added in T417: what S9 does with each segment, in frames (stages/s9_visuals.py `plan`)."""
    box = {"x": 0.08, "y": 0.12, "w": 0.3, "h": 0.3}

    def case(desc, storyboard, focus=box, src=450, expected=None):
        return {**({"expected": expected} if expected else {}), "area": "visuals", "function": "visuals.plan", "language": None, "description": desc,
                "tolerance_s": 0.001,
                "inputs": {"storyboard": storyboard, "focus_box": value(focus), "src_frames": value(src)}}  # fmt: skip

    def sb(**patch):
        return sample("storyboard", **({"merge_patch": patch} if patch else {}))

    def other(name):
        return {"file": f"../../storyboard/{name}/expected/output.json"}

    return {
        "sample_run": case(
            "The sample storyboard: cut, the replay at 0.25 with its zoom, held frames, frame counts that add up.",
            sb(),
        ),
        "no_focus_box": case("No focus box: the replay has no zoom crop.", sb(), focus=None),
        "climax_on_last_frame": case(
            "The replay holds its last frame (hold_s) and still has its frame count.",
            other("climax_on_last_frame"),
        ),
        "clip_3s": case(
            "A 3 s clip: the replay window widened, the end card long.", other("clip_3s")
        ),
        "clip_30s": case("A 30 s clip trimmed around the climax.", other("clip_30s")),
        "source_shorter_than_the_storyboard": case(
            "A working copy of 20 frames: every source range and held frame is clamped to it.",
            sb(),
            src=20,
        ),
        "portrait_1080x1920": case(
            "A portrait clip: the crop is of the portrait frame.", sb(width=1080, height=1920)
        ),
        "focus_box_in_a_corner": case(
            "A focus box in the top-left corner: the crop stops at the edges, as the sample's does (its box is near the corner too).",
            sb(),
            focus={"x": 0.0, "y": 0.0, "w": 0.1, "h": 0.1},
            expected={"file": "../sample_run/expected/output.json"},
        ),
        "focus_box_bottom_right": case(
            "The bottom-right corner.", sb(), focus={"x": 0.9, "y": 0.9, "w": 0.1, "h": 0.1}
        ),
        "focus_box_in_the_middle": case(
            "A box in the middle of the frame: the crop is centred on it.",
            sb(),
            focus={"x": 0.4, "y": 0.35, "w": 0.2, "h": 0.2},
        ),
        "crop_centre_halfway_between_pixels": case(
            "The crop's corner falls on x.5: it is rounded half to even (98, not 99).",
            sb(width=853, height=481, segments=_zoomed_segments(1.3)),
            focus={"x": 0.45, "y": 0.4, "w": 0.1, "h": 0.2},
        ),
        "crop_size_rounds_down_to_even": case(
            "777 x 557 at a zoom of 1.25: 621.6 and 445.6 become 620 and 444, never odd.",
            sb(width=777, height=557),
        ),
        "an_empty_source_range_keeps_one_frame": case(
            "A segment whose source range is empty (in = out) still cuts one frame.",
            sb(segments=_empty_range_segments()),
        ),
        "odd_size_and_scale": case(
            "An odd frame size and a zoom of 1.3: the crop is rounded down to even numbers, the centre half to even.",
            sb(width=853, height=481, segments=_zoomed_segments(1.3)),
        ),
        "fps_25": case("25 fps: frame numbers from times, rounding half to even.", sb(fps=25)),
        "fps_29_97": case("29.97 fps.", sb(fps=29.97)),
    }


def _empty_range_segments() -> list[dict]:
    segs = json.loads((paths.SAMPLE_RUN / "storyboard.json").read_text())["segments"]
    return [dict(s, source={"in_s": 2.0, "out_s": 2.0}) if s["kind"] == "live" else s for s in segs]


def _zoomed_segments(scale: float) -> list[dict]:
    segs = json.loads((paths.SAMPLE_RUN / "storyboard.json").read_text())["segments"]
    return [dict(s, zoom=({**s["zoom"], "scale": scale} if s["zoom"] else None)) for s in segs]


def _recorded_answers() -> tuple[list[dict], list[dict]]:
    """The model's real answers in the PC's vision recordings that fit their schema (the adapter retries the others, so
    S3 never sees them): (overview answers, events answers), in file order."""
    import jsonschema

    from epic_replay.agents import spotter

    ov, ev = [], []
    for f in sorted((paths.ROOT / "pc/tests/fixtures/recordings/vision").glob("*.json")):
        data = json.loads(f.read_text()).get("data")
        if isinstance(data, dict) and "events" in data:
            if jsonschema.Draft202012Validator(spotter.EVENTS_SCHEMA).is_valid(data):
                ev.append(data)
        elif isinstance(data, dict) and "high_point_cell" in data:
            if jsonschema.Draft202012Validator(spotter.OVERVIEW_SCHEMA).is_valid(data):
                ov.append(data)
    return ov, ev


def spotter_cases() -> dict[str, dict]:
    """Added in T418: the Spotter's plain code on the model's answers (agents/spotter.py, stages/s3_key_moment.py)."""
    from epic_replay.agents import spotter

    ov, ev_all = _recorded_answers()
    ev = [
        a for a in ev_all if not a.get("sensitive")
    ]  # the sensitive ones are used where the case says so

    def cand(f, score, sound_z=5.0, onset=None):
        return {"frame": f, "t": round(f / 30, 3), "score": score, "motion_z": 9.0, "flow_z": 9.0, "change_z": 3.0,
                "sound_z": sound_z, "sound_onset_t": None if onset is None else round(onset / 30, 3),
                "sound_onset_frame": onset, "loud_peak_t": None}  # fmt: skip

    CANDS = [
        cand(313, 24.0, onset=311),
        cand(289, 18.5, 0.0),
        cand(120, 14.0, onset=118),
        cand(60, 12.2, 0.0),
        cand(400, 11.0, onset=399),
    ]
    meta = {"duration_s": 15.0, "fps": 30, "width": 1920, "height": 1080}

    def info(frames=450, fps=30.0, cands=CANDS, user=None):
        return {"duration_s": round(frames / fps, 3), "fps": fps, "frames": frames, "orientation": "landscape",
                "candidates": cands, "user": user or {"activity": None, "player_name": None, "description": None}}  # fmt: skip

    def sheets_of(inf):
        """The plan's sheets with their cells, as the Spotter writes them."""
        out = []
        for e in spotter.plan_overview(inf):
            fps = inf["fps"]
            out.append({"kind": e["kind"], "name": e["name"],
                        "cells": [{"cell": k + 1, "frame": f, "t": round(f / fps, 3)} for k, f in enumerate(e["frames"])]})  # fmt: skip
        return out

    def looks(inf, answers):
        """Each sheet with its answer (None: left out); a longer list than sheets is cut."""
        return [dict(sh, answer=a) if a is not None else dict(sh, answer=None, left_out="Timeout: timed out")
                for sh, a in zip(sheets_of(inf), answers, strict=False)]  # fmt: skip

    def events_look(inf, answer):
        grids = [sh["cells"] for sh in sheets_of(inf)[:2]]
        if answer is None:
            return {
                "grids": ["o0_uniform.jpg", "o1_uniform.jpg"],
                "answer": None,
                "events": None,
                "left_out": "HTTPStatusError: 500",
            }
        return {"grids": ["o0_uniform.jpg", "o1_uniform.jpg"], "answer": answer, "events": spotter.map_events(answer, grids)}  # fmt: skip

    def sheet_case(desc, inf, answers, ev_answer, signals=None, snap=0.25, clip=meta):
        return {"area": "spotter", "function": "spotter.play_sheet", "language": None, "description": desc,
                "tolerance_s": 0.001,
                "inputs": {"run_id": value("run-1"), "info": value(inf), "looks": value(looks(inf, answers)),
                           "events_look": value(events_look(inf, ev_answer)),
                           "signals": value(signals or {"onsets_t": [round(311 / 30, 3), round(118 / 30, 3)]}),
                           "snap_s": value(snap), "model": value("deepseek-flash"), "clip_meta": value(clip)}}  # fmt: skip

    inf0 = info()
    out: dict[str, dict] = {}
    # the plans
    for name, desc, inf in (
        (
            "plan_15s",
            "A 15 s clip with five S1 candidates: two even sheets of nine, three candidate sheets around the top three.",
            inf0,
        ),
        (
            "plan_3s",
            "A 3 s clip: windows under nine frames become nine consecutive frames.",
            info(frames=90, cands=CANDS[:2]),
        ),
        ("plan_no_candidates", "No S1 candidate: only the two even sheets.", info(cands=[])),
        ("plan_29_97_fps", "29.97 fps, 299 frames.", info(frames=299, fps=29.97, cands=CANDS[:3])),
        (
            "plan_tiny_clip",
            "A clip of 20 frames: the even frames are fewer than eighteen and are not repeated.",
            info(frames=20, cands=CANDS[:1]),
        ),
    ):
        out[name] = {"area": "spotter", "function": "spotter.plan_overview", "language": None, "description": desc,
                     "inputs": {"info": value(inf)}}  # fmt: skip
    grids = [sh["cells"] for sh in sheets_of(inf0)[:2]]
    odd = {"events": [
        {"first_grid": 2, "first_cell": 9, "last_grid": 1, "last_cell": 1, "actor": "  a dog ", "action": " jumps ", "outcome": "  "},
        {"first_grid": 0, "first_cell": 0, "last_grid": 5, "last_cell": 12, "actor": "a dog", "action": "runs", "outcome": "done"},
        {"first_grid": 1, "first_cell": 3, "last_grid": 1, "last_cell": 3, "actor": "a cat", "action": "sits", "outcome": ""},
    ]}  # fmt: skip
    for name, desc, answers in (
        (
            "map_real_answers_first",
            "Real answers of the cloud model: each event's grid and cells become times, in time order.",
            ev[:13],
        ),
        ("map_real_answers_rest", "More real answers.", ev[13:]),
        (
            "map_edges",
            "A cell or grid out of range is clamped, an end before its start is the start, text is stripped, an empty outcome is null, events come out sorted.",
            [odd],
        ),
    ):
        out[name] = {"area": "spotter", "function": "spotter.map_events", "language": None, "description": desc,
                     "tolerance_s": 0.001, "inputs": {"answers": value(answers), "grids": value(grids)}}  # fmt: skip
    sens = [
        None,
        {"answer": None},
        {"answer": {"sensitive": False, "sensitive_reason": "ignored", "events": []}},
        {"answer": {"sensitive": True, "sensitive_reason": "someone is hurt."}},
        {"answer": {"sensitive": True, "sensitive_reason": "a teenage boy is bullied"}},
        {"answer": {"sensitive": True, "sensitive_reason": ""}},
        {"answer": {"sensitive": True, "sensitive_reason": "x" * 121}},
        {"answer": {"sensitive": True, "sensitive_reason": "x" * 120}},
        {"answer": {"sensitive": "true", "sensitive_reason": "stringly"}},
        {"answer": {"sensitive": True}},
        {"answer": {"sensitive": True, "sensitive_reason": "  a fight breaks out.. "}},
    ]
    sens += [{"answer": a} for a in ev_all if a.get("sensitive")]
    out["sensitivity"] = {"area": "spotter", "function": "spotter.sensitivity", "language": None,
                          "description": "The sensitivity flag from the events call: none or no answer is unchecked; a reason with a descriptor word, an empty one or one over 120 characters is replaced by the plain default; only a true boolean raises the flag.",
                          "inputs": {"looks": value(sens)}}  # fmt: skip
    # the play sheet, from real answers: five sheets and the events call each, drawn from the recordings
    for k in range(6):
        answers = [ov[(k * 29 + j * 7) % len(ov)] for j in range(5)]
        out[f"play_sheet_real_{k + 1}"] = sheet_case(
            f"Real answers of the cloud model for the five sheets and the events call ({k + 1} of 6): the play sheet S3 writes.",
            inf0,
            answers,
            ev[(k * 4) % len(ev)],
        )
    best = {
        "activity": "a ball is struck",
        "outcome": "the ball flies off",
        "actor": "player in a red shirt",
        "high_point_cell": 5,
        "sure": 8,
    }
    weak = dict(best, sure=4)
    unseen = dict(best, high_point_cell=0, sure=3)
    out["play_sheet_snaps_to_a_sound_onset"] = sheet_case(
        "The proposed frame moves to a sound onset up to 0.25 s before it (the method says so).",
        inf0,
        [weak, weak, best, weak, weak],
        ev[1],
    )
    out["play_sheet_a_candidate_sheet_wins_a_tie"] = sheet_case(
        "Equal sureness: the candidate sheet wins; between sheets of one kind the earlier.",
        inf0,
        [best, best, best, best, best],
        ev[2],
    )
    out["play_sheet_nobody_saw_the_high_point"] = sheet_case(
        "No sheet shows the high point: the strongest S1 candidate is proposed at its sound onset.",
        inf0,
        [unseen] * 5,
        ev[3],
    )
    out["play_sheet_every_sheet_failed"] = sheet_case(
        "Every sheet was left out and so was the events call: the candidate is proposed, two events, the sensitivity unchecked.",
        inf0,
        [None] * 5,
        None,
    )
    out["play_sheet_no_candidates_no_answer"] = sheet_case(
        "Nothing answered and S1 found nothing: the middle of the clip, for the user to move.",
        info(cands=[]),
        [None] * 5,
        None,
        signals={"onsets_t": []},
    )
    out["play_sheet_events_call_failed"] = sheet_case(
        "Only the events call failed: two events, the description from the sheets.",
        inf0,
        [weak, weak, best, weak, weak],
        None,
    )
    out["play_sheet_the_user_typed_an_activity"] = sheet_case(
        "What the user typed wins over the model's activity, and the user's name goes on the followed actor.",
        info(user={"activity": "free kick", "player_name": "Noa", "description": None}),
        [weak, weak, best, weak, weak],
        ev[4],
    )
    out["play_sheet_a_second_high_point"] = sheet_case(
        "A high point the overview saw 0.5 s or more from the others is listed as another moment, up to six.",
        inf0,
        [
            dict(best, high_point_cell=2),
            dict(best, high_point_cell=8),
            best,
            dict(best, high_point_cell=1),
            weak,
        ],
        ev[5],
    )
    out["play_sheet_a_descriptor_breach_is_emptied"] = sheet_case(
        "Model-written fields with an age word (the outcome, an actor) are emptied and flagged for the user, and the play sheet is accepted again.",
        inf0,
        [weak, weak, dict(best, outcome="a young boy scores"), weak, weak],
        dict(
            ev[6], events=[dict(ev[6]["events"][0], actor="a young player")] + ev[6]["events"][1:]
        ),
    )

    # hand-made traces for the edges the real answers do not reach
    def raw_case(desc, inf, sheets, ev_look, signals=None, clip=meta):
        return {"area": "spotter", "function": "spotter.play_sheet", "language": None, "description": desc, "tolerance_s": 0.001,
                "inputs": {"run_id": value("run-1"), "info": value(inf), "looks": value(sheets), "events_look": value(ev_look),
                           "signals": value(signals or {"onsets_t": []}), "snap_s": value(0.25), "model": value("deepseek-flash"),
                           "clip_meta": value(clip)}}  # fmt: skip

    def mk_sheet(kind, name, frames, answer):
        return {"kind": kind, "name": name, "answer": answer,
                "cells": [{"cell": k + 1, "frame": f, "t": round(f / 30, 3)} for k, f in enumerate(frames)]}  # fmt: skip

    def ans(cell, sure=8):
        return {
            "activity": "a kick",
            "outcome": "it flies",
            "actor": "player in red",
            "high_point_cell": cell,
            "sure": sure,
        }

    gap_sheets = [
        mk_sheet("candidate", "o2_candidate.jpg", [300, 305, 311, 314, 318, 320, 322, 324, 326], ans(3)),
        mk_sheet("uniform", "o0_uniform.jpg", [300, 305, 311, 314, 318, 320, 322, 324, 326], ans(4, 3)),
        mk_sheet("uniform", "o1_uniform.jpg", [300, 305, 311, 314, 318, 325, 326, 330, 340], ans(6, 2)),
        mk_sheet("uniform", "o3_uniform.jpg", [300, 305, 311, 314, 318, 320, 322, 324, 326], ans(9, 1)),
    ]  # fmt: skip
    nocand = info(cands=[])
    out["play_sheet_other_moments_half_a_second_apart"] = raw_case(
        "A second high point 14 frames (0.467 s) from the proposal is left out, one 15 frames (0.5 s) away is listed.",
        nocand,
        gap_sheets,
        None,
    )
    edge_events = {"answer": {"sure": 7, "sensitive": False, "sensitive_reason": "", "events": []}, "grids": [], "events": [
        {"t_start": 1.0, "t_end": 2.0, "actor": "a runner", "action": "starts", "outcome": None},
        {"t_start": 9.0, "t_end": 15.001, "actor": "a runner", "action": "finishes", "outcome": "wins"}]}  # fmt: skip
    out["play_sheet_an_event_a_millisecond_past_the_end"] = raw_case(
        "An event that ends 0.001 s after the clip does: within the 0.002 s the rules allow.",
        nocand,
        [
            mk_sheet(
                "candidate",
                "o2_candidate.jpg",
                [300, 305, 311, 314, 318, 320, 322, 324, 326],
                ans(3),
            )
        ],
        edge_events,
    )
    out["play_sheet_a_sound_just_after_the_frame"] = raw_case(
        "A sound onset up to 0.1 s after the proposed frame counts for the snap.",
        nocand,
        [
            mk_sheet(
                "candidate",
                "o2_candidate.jpg",
                [300, 305, 311, 314, 318, 320, 322, 324, 326],
                ans(3),
            )
        ],
        None,
        signals={"onsets_t": [round(311 / 30 + 0.08, 3)]},
    )
    overlap = {"answer": {"sure": 7, "sensitive": False, "sensitive_reason": "", "events": []}, "grids": [], "events": [
        {"t_start": 8.0, "t_end": 12.0, "actor": "a dog", "action": "runs", "outcome": None},
        {"t_start": 10.0, "t_end": 12.0, "actor": "a dog", "action": "jumps", "outcome": None},
        {"t_start": 9.0, "t_end": 12.0, "actor": "a cat", "action": "watches", "outcome": None}]}  # fmt: skip
    out["play_sheet_the_climax_event_of_equals_is_the_latest"] = raw_case(
        "Three events all hold the proposed moment: the climax is the one that starts last.",
        nocand,
        [
            mk_sheet(
                "candidate",
                "o2_candidate.jpg",
                [300, 305, 311, 314, 318, 320, 322, 324, 326],
                ans(3),
            )
        ],
        overlap,
    )
    breach = {"answer": {"sure": 7, "sensitive": False, "sensitive_reason": "", "events": []}, "grids": [], "events": [
        {"t_start": 8.0, "t_end": 12.0, "actor": "a dog", "action": "chases a young boy", "outcome": "a girl laughs"}]}  # fmt: skip
    out["play_sheet_a_breach_in_an_event_is_emptied_to_null"] = raw_case(
        "A breach in an event's action or outcome: the action becomes an empty string, the outcome null.",
        nocand,
        [
            mk_sheet(
                "candidate",
                "o2_candidate.jpg",
                [300, 305, 311, 314, 318, 320, 322, 324, 326],
                ans(3),
            )
        ],
        breach,
    )
    out["play_sheet_a_sensitive_clip"] = sheet_case(
        "The events call says the clip is sensitive: the flag and the reason are in the play sheet (the run stops there).",
        inf0,
        [best] * 5,
        next(a for a in ev_all if a.get("sensitive")),
    )
    out["play_sheet_clip_meta_rejects"] = sheet_case(
        "A clip metadata shorter than the proposed moment: the play sheet is not accepted, with the reasons.",
        inf0,
        [weak, weak, best, weak, weak],
        ev[7],
        clip={**meta, "duration_s": 4.0},
    )
    return out


def commentator_cases() -> dict[str, dict]:
    """Added in T419: the Commentator's plain code (agents/commentator.py): what it is told, how a draft is judged and mended."""
    from epic_replay.agents import commentator
    from epic_replay.styles import loader

    ps = json.loads((paths.SAMPLE_RUN / "play_sheet.json").read_text())
    sb = json.loads((paths.SAMPLE_RUN / "storyboard.json").read_text())
    uc = json.loads((paths.SAMPLE_RUN / "user_context.json").read_text())
    script = json.loads((paths.SAMPLE_RUN / "script.json").read_text())
    packs = loader.load()

    def slots_ref(style, lang):
        return {"file": f"../../slots/{style}_{lang}/expected/output.json"}

    def good_draft(sc=script):
        out = []
        for ln in sc["lines"]:
            d = {
                "id": ln["id"],
                "text": ln["text"],
                "delivery": ln["delivery"],
                "event_refs": ln["event_refs"],
            }
            if ln.get("hit_word"):
                d["hit_word"] = ln["hit_word"]
            out.append(d)
        return {"lines": out, "headline": sc["headline"], "lower_third": sc["lower_third"]}

    def with_line(d, i, **change):
        d = json.loads(json.dumps(d))
        d["lines"][i].update(change)
        return d

    def case(function, desc, inputs, lang="en", tol=None):
        c = {
            "area": "commentator",
            "function": function,
            "language": lang,
            "description": desc,
            "inputs": inputs,
        }
        if tol:
            c["tolerance_s"] = tol
        return c

    def common(style="football_commentator", lang="en", **more):
        return {"slots": slots_ref(style, lang), "play_sheet": sample("play_sheet"),
                "user_context": sample("user_context", **({"merge_patch": more["uc"]} if "uc" in more else {})),
                "storyboard": sample("storyboard"), "pack": {"style": style, "language": lang}}  # fmt: skip

    out: dict[str, dict] = {}
    # what it is told
    for style in packs:
        for lang in ("en", "zh"):
            out[f"system_prompt_{style}_{lang}"] = case(
                "commentator.system_prompt", f"The system prompt of {style} for a run in {'English' if lang == 'en' else 'Mandarin'}.",
                {"pack": {"style": style, "language": lang}}, lang)  # fmt: skip
    base = {"slots": slots_ref("football_commentator", "en"), "play_sheet": sample("play_sheet"),
            "user_context": sample("user_context"), "storyboard": sample("storyboard")}  # fmt: skip
    out["brief_sample_run"] = case(
        "commentator.brief",
        "The brief of the sample run: the events a script may use, the names the user gave, what each slot shows.",
        base,
        tol=0.001,
    )
    events = [dict(e) for e in ps["events"]]
    events[1] = dict(events[1], action="on-screen caption says FREE KICK")
    events[2] = dict(events[2], confidence=0.69)
    events[6] = dict(events[6], confidence=0.1, source="user")
    actors = [dict(a) for a in ps["actors"]]
    actors[2] = dict(actors[2], descriptor="a title banner")
    events[4] = dict(events[4], actor="a3")
    out["brief_caption_and_unsure_events"] = case(
        "commentator.brief",
        "An event that is a caption, one an actor that is a banner describes, one just under 0.7 sure are not shown; one the user wrote is, however unsure.",
        {**base, "play_sheet": {"fixture": "sample_run/play_sheet.json", "merge_patch": {"events": events, "actors": actors}},
         "user_context": sample("user_context", merge_patch={"description": "my son's best kick", "team_home": "Blue"})}, tol=0.001)  # fmt: skip
    out["brief_zh_slots"] = case(
        "commentator.brief",
        "The brief for the Mandarin slots.",
        {**base, "slots": slots_ref("news_reporter", "zh")},
        "zh",
        tol=0.001,
    )
    # how a draft is judged
    gd = good_draft()
    drafts = {
        "a draft that passes": gd,
        "a slot with no line": {**gd, "lines": gd["lines"][:-1]},
        "a key line with no hit word": {
            **gd,
            "lines": [{k: v for k, v in ln.items() if k != "hit_word"} for ln in gd["lines"]],
        },
        "a hit word not in the line": with_line(gd, 2, hit_word="BANANA"),
        "a hit word with its punctuation": with_line(gd, 2, hit_word="top!"),
        "an invented name": with_line(gd, 0, text="Ronaldo steps up."),
        "an invented number": with_line(gd, 0, text="Seven men in the wall."),
        "a banned term": with_line(gd, 0, text="She steps up."),
        "an unknown event": with_line(gd, 0, event_refs=["e99"]),
        "no event refs at all": with_line(gd, 0, event_refs=[]),
        "a line over its budget": with_line(gd, 1, text="Noa takes a very long run up now"),
        "an empty text": with_line(gd, 3, text=""),
        "a text of spaces": with_line(gd, 3, text="   "),
        "a headline with a name": {**gd, "headline": "RONALDO SCORES"},
        "a repeated event ref and a number as a ref": with_line(gd, 0, event_refs=["e1", "e1", 7]),
        "a line that is not an object": {**gd, "lines": [*gd["lines"][:6], "l7"]},
        "no lines at all": {"lines": [], "headline": "", "lower_third": ""},
        "a delivery of nothing": with_line(gd, 0, delivery=""),
        "a long headline": {**gd, "headline": "X" * 41},
    }
    out["failures_en"] = case("commentator.failures", "Every failure of each of nineteen drafts: " + "; ".join(drafts) + ".",
                              {**common(), "drafts": value(list(drafts.values()))})  # fmt: skip
    # Mandarin: a passing draft is the generic lines; the rest is made from it
    zh_tools = commentator.Tools(
        json.loads((paths.CONFORMANCE / "slots/news_reporter_zh/expected/output.json").read_text()),
        ps,
        uc,
        sb,
        loader.resolved(packs["news_reporter"], "zh"),
    )
    zd, _ = zh_tools.patched({})
    zd = json.loads(json.dumps(zd))

    def zline(d, i, **c):
        d = json.loads(json.dumps(d))
        d["lines"][i].update(c)
        return d

    zdrafts = [zd, zline(zd, 0, text="Noa 开始。"), zline(zd, 0, text="3 开始。"), zline(zd, 0, text="開始。"), zline(zd, 0, text="start"),
               zline(zd, 2, hit_word="没有"), zline(zd, 2, hit_word="一二三四"), zline(zd, 0, text="她开始。"), {**zd, "headline": "好" * 17},
               {**zd, "lower_third": "好" * 16}, zline(zd, 0, text="梅西开始。"), zline(zd, 3, delivery="好！"), zline(zd, 2, delivery="quiet")]  # fmt: skip
    out["failures_zh"] = case("commentator.failures", "Mandarin drafts: a Latin word, a digit, a Traditional character, no Chinese at all, a hit word that is not in the line or is too long, 她, a headline over 16 characters, a famous name.",
                              {**common("news_reporter", "zh"), "drafts": value(zdrafts)}, "zh")  # fmt: skip
    # how it is mended
    slot_file = json.loads(
        (paths.CONFORMANCE / "slots/football_commentator_en/expected/output.json").read_text()
    )
    near = [
        dict(
            sl, anchor=dict(sl["anchor"], timeline_t=round(sl["anchor"]["timeline_t"] + 0.0015, 4))
        )
        if sl["role"] == "climax"
        else sl
        for sl in slot_file
    ]
    far = [
        dict(
            sl, anchor=dict(sl["anchor"], timeline_t=round(sl["anchor"]["timeline_t"] + 0.0025, 4))
        )
        if sl["role"] == "climax"
        else sl
        for sl in slot_file
    ]
    out["failures_anchor_off_the_marker"] = case(
        "commentator.failures", "A key line's anchor 0.0015 s from its marker is the marker (within 0.002 s); 0.0025 s away it is not.",
        {**common(), "slots": value(near), "drafts": value([gd])}, tol=0.001)  # fmt: skip
    out["failures_anchor_far_from_the_marker"] = case(
        "commentator.failures", "The same anchor 0.0025 s from its marker: the hit_at anchor is not a storyboard marker.",
        {**common(), "slots": value(far), "drafts": value([gd])}, tol=0.001)  # fmt: skip
    zlong = zline(zd, 2, text="好球好球好球", hit_word="好球好球")
    out["failures_zh_long_hit_word"] = case(
        "commentator.failures", "A Mandarin hit word of four characters that is in the line is still too long (one to three).",
        {**common("news_reporter", "zh"), "drafts": value([zlong, zline(zd, 2, text="好球好球好球", hit_word="好球好"), zline(zd, 2, text="好球好球好球", hit_word="  好！ ")])}, "zh")  # fmt: skip
    out["patched_en"] = case("commentator.patched", "Failing lines, missing ones and a failing headline are replaced by generic lines and the pack's labels; a draft that is not an object or has no lines gets generic lines for all.",
                             {**common(), "drafts": value([gd, drafts["an invented name"], drafts["a slot with no line"], drafts["a banned term"], drafts["a line over its budget"],
                                                        {}, {"lines": "x"}, {"lines": [3, None], "headline": 5}, drafts["a headline with a name"], drafts["a long headline"]])})  # fmt: skip
    out["patched_zh"] = case(
        "commentator.patched",
        "The same in Mandarin.",
        {**common("news_reporter", "zh"), "drafts": value(zdrafts + [{}])},
        "zh",
    )
    # what comes out
    bad = drafts["an invented name"]
    out["script_the_agents_draft"] = case(
        "commentator.script",
        "The agent's draft passes: it is the script, status ok.",
        {**common(), "agent_draft": value(gd), "structured_draft": value(None)},
        tol=0.001,
    )
    out["script_the_structured_draft"] = case(
        "commentator.script",
        "The agent's draft fails: the structured draft is used, status degraded.",
        {**common(), "agent_draft": value(bad), "structured_draft": value(gd)},
        tol=0.001,
    )
    out["script_a_mended_draft"] = case(
        "commentator.script",
        "Neither draft passes: the failing lines are generic, the rest kept.",
        {**common(), "agent_draft": value(None), "structured_draft": value(bad)},
        tol=0.001,
    )
    out["script_nothing_at_all"] = case(
        "commentator.script",
        "No agent draft and the structured call failed: every line is generic.",
        {**common(), "agent_draft": value(None), "structured_draft": value(None)},
        tol=0.001,
    )
    out["script_zh_mended"] = case(
        "commentator.script",
        "Mandarin: a draft with no Chinese in a line is mended.",
        {
            **common("news_reporter", "zh"),
            "agent_draft": value(zdrafts[4]),
            "structured_draft": value(None),
        },
        "zh",
        tol=0.001,
    )
    zok = {
        **zd,
        "lines": [
            dict(ln, delivery=d)
            for ln, d in zip(
                zd["lines"],
                ["calm", "quiet", "a shout", "好！", "slow", "slow", "calm"],
                strict=True,
            )
        ],
    }
    out["script_zh_deliveries"] = case(
        "commentator.script", "A Mandarin draft: a climax keeps the pack's delivery, a direction that is not English words is not used, English words are.",
        {**common("news_reporter", "zh"), "agent_draft": value(zok), "structured_draft": value(None)}, "zh", tol=0.001)  # fmt: skip
    out["script_not_expressed"] = case(
        "commentator.script", "A user context with express false: the script is not approved by the user in advance (approved: no).",
        {**common(uc={"express": False}), "agent_draft": value(gd), "structured_draft": value(None)}, tol=0.001)  # fmt: skip
    # real drafts: what the agent submitted in the PC's recorded runs of the sample (recordings keyed on these exact prompts)
    from epic_replay.providers import llm
    from epic_replay.providers.replay import cache_key
    from epic_replay.styles import slots as slot_rules

    model = llm.agent_endpoint().model
    for style in ("news_reporter", "nature_documentary", "football_commentator"):
        pack = loader.resolved(packs[style], "en")
        built = slot_rules.build(sb, pack, climax_event_id=ps["climax"].get("event_id"))
        system = commentator.system_prompt(pack)
        query = "Brief (data):\n" + json.dumps(commentator.brief(ps, uc, sb, built), indent=1)
        rec = (
            paths.ROOT
            / "pc/tests/fixtures/recordings/commentator"
            / f"{cache_key('commentator', model, {'system': system, 'query': query})}.json"
        )
        if not rec.exists():
            continue
        draft = json.loads(rec.read_text()).get("draft")
        out[f"script_recorded_run_{style}"] = case(
            "commentator.script", f"The draft the agent submitted in the PC's recorded run of the sample with {style}: it passes and is the script.",
            {**common(style), "agent_draft": value(draft), "structured_draft": value(None)}, tol=0.001)  # fmt: skip
    for style in packs:
        out[f"generic_{style}"] = case(
            "commentator.generic",
            f"Generic mode, {style}: no model, every line generic, the pack's labels for the headline and the lower third.",
            common(style),
            tol=0.001,
        )
    out["generic_news_reporter_zh"] = case(
        "commentator.generic",
        "Generic mode in Mandarin.",
        common("news_reporter", "zh"),
        "zh",
        tol=0.001,
    )
    return out


def intake_cases() -> dict[str, dict]:
    """Added in T420: S0's rules on a probe (stages/s0_intake.py): what is refused and why, and the clip metadata."""
    mb = 1024 * 1024

    def stream(kind, **more):
        return {"codec_type": kind, **more}

    video = stream("video", width=320, height=180, avg_frame_rate="25/1", r_frame_rate="25/1")
    sound = stream("audio")

    def info(dur, streams=None, fmt=True):
        out = {"streams": streams if streams is not None else [video, sound]}
        if fmt:
            out["format"] = {"duration": dur}
        return out

    def case(function, desc, inputs):
        return {
            "area": "intake",
            "function": function,
            "language": None,
            "description": desc,
            "inputs": inputs,
        }

    def f(name, size, i):
        return {"name": name, "size_bytes": size, "info": i}

    out = {}
    out["check_what_is_refused"] = case(
        "intake.check", "The extension, the size, the video stream and the length, in that order, each with its words for the page.",
        {"files": value([
            f("upload.mp4", 5 * mb, info("4.000000")), f("clip.MOV", 5 * mb, info("4.0")), f("a.webm", 1, info("4.0")),
            f("upload.avi", 5 * mb, info("4.0")), f("upload.mkv", 5 * mb, info("4.0")), f("upload", 5 * mb, info("4.0")),
            f("upload.mp4.avi", 5 * mb, info("4.0")), f(".mp4", 5 * mb, info("4.0")),
            f("upload.mp4", 200 * mb, info("4.0")), f("upload.mp4", 200 * mb + 1, info("4.0")), f("upload.avi", 300 * mb, info("4.0")),
            f("upload.mp4", 5 * mb, {}), f("upload.mp4", 5 * mb, info("4.0", streams=[sound])), f("upload.mp4", 5 * mb, info("4.0", streams=[])),
            f("upload.mp4", 5 * mb, {"format": {"duration": "4.0"}}),
        ])})  # fmt: skip
    out["check_the_length"] = case(
        "intake.check", "3 to 30 seconds with 0.05 s of slack at each end, and how a length is written in the words: one decimal.",
        {"files": value([f("upload.mp4", mb, info(d)) for d in ["2.94", "2.95", "2.949999", "3.0", "29.99", "30.0", "30.05", "30.0500001", "30.06", "40.0", "2.0",
                                                                "0", "0.0", None, "N/A", "abc", 12.5, 3, "1e1", " 7 "]]
                          + [f("upload.mp4", mb, {"streams": [video], "format": {}}), f("upload.mp4", mb, {"streams": [video]})])})  # fmt: skip
    out["check_the_words_round_half_to_even"] = case(
        "intake.check", "The length in the words is written to one decimal, rounding half to even as Python does (0.25 is 0.2, 2.25 is 2.2, 30.25 is 30.2).",
        {"files": value([f("upload.mp4", mb, info(d)) for d in ["0.25", "0.35", "2.25", "2.35", "30.25", "30.35", "45.05", "99.95"]])})  # fmt: skip
    out["work_check"] = case(
        "intake.work_check", "The working copy: a video stream and a length of 3 to 30 s on what could be read, in other words than the upload's.",
        {"infos": value([info("4.0"), {}, info("4.0", streams=[sound]), info("1.2"), info("29.9"), info("31.5"), info("2.95"), info("30.05"), info("30.06"), info(None)])})  # fmt: skip
    out["capture_fps"] = case(
        "intake.capture_fps", "The upload's own frame rate: the average, else the container's, else 30; 29.97 and 120 are kept.",
        {"streams": value([
            {"avg_frame_rate": "30000/1001", "r_frame_rate": "30/1"}, {"avg_frame_rate": "0/0", "r_frame_rate": "240/1"},
            {"avg_frame_rate": "0/0", "r_frame_rate": "0/0"}, {}, None, {"avg_frame_rate": "25/1"}, {"avg_frame_rate": "24000/1001"},
            {"avg_frame_rate": "60"}, {"avg_frame_rate": "abc/1", "r_frame_rate": "50/1"}, {"avg_frame_rate": "1/3"}, {"avg_frame_rate": "-30/1", "r_frame_rate": "12/1"},
            {"avg_frame_rate": "2997/100"}, {"avg_frame_rate": "10/0", "r_frame_rate": "15/1"}])})  # fmt: skip
    out["work_size"] = case(
        "intake.work_size", "The working copy's size for an upright picture: the height capped at 1080 lines, the width following the picture and even (measured with FFmpeg's own scale filter). Only pictures that FFmpeg 6 and 8 size alike are here: an odd picture that needs no scaling (853 by 480) is made 852 wide by one and 854 by the other; clips are even.",
        {"sizes": value([[1920, 1080], [1080, 1920], [3840, 2160], [2160, 3840], [1280, 720], [854, 480], [1000, 1081], [1001, 1081],
                         [4032, 3024], [3024, 4032], [1440, 1080], [1081, 1081], [320, 180], [180, 320], [64, 36], [2, 2], [1919, 1081], [1366, 768], [2560, 1440], [607, 1081]])})  # fmt: skip
    sha = "a" * 64
    out["meta"] = case(
        "intake.meta", "clip_meta.json from the upload's and the working copy's probes: the length to the millisecond, the working copy's size, 30 fps, the upload's own rate and whether it had sound.",
        {"items": value([
            {"src_info": info("4.04", [video, sound]), "work_info": info("4.0333", [dict(video, width=320, height=180), sound]), "sha256": sha},
            {"src_info": info("3.0", [dict(video, avg_frame_rate="120/1")]), "work_info": info("3.0005", [dict(video, width=180, height=320)]), "sha256": sha},
            {"src_info": info("30.0", [dict(video, avg_frame_rate="0/0", r_frame_rate="0/0"), sound, sound]), "work_info": info("29.9995", [dict(video, width=1920, height=1080)]), "sha256": sha},
            {"src_info": info("12.3456", [sound, video]), "work_info": info("12.3465", [video]), "sha256": sha},
        ])})  # fmt: skip
    return out


def speech_cases() -> dict[str, dict]:
    """Added in T422: the voice's requests and the measures that place a take (providers/speech.py, media/audio.py)."""

    def case(function, desc, inputs, tol=None):
        c = {
            "area": "speech",
            "function": function,
            "language": None,
            "description": desc,
            "inputs": inputs,
        }
        if tol:
            c["tolerance_s"] = tol
        return c

    def take(
        text,
        delivery="",
        language="en",
        timings=False,
        voice="fGIZlgPQ75MMlvQ6WxgY",
        model="eleven_v3",
        key="test-key",
    ):
        return {
            "text": text,
            "voice_id": voice,
            "delivery": delivery,
            "language": language,
            "timings": timings,
            "model": model,
            "key": key,
        }

    out = {}
    out["request_english"] = case(
        "speech.request", "One take per request: the URL of the voice, the key in a header, the delivery as audio tags before the words, the estimate at 0.10 USD per 1,000 characters sent (tags included), the recording key.",
        {"takes": value([take("Free kick, just outside the box.", "measured, expectant"), take("TOP CORNER!", "shouting, full voice", timings=True),
                         take("Noa...", "rising, quick"), take("Magnificent.", ""), take("Nice", " calm ,, slow  "), take("Wow", ",")])})  # fmt: skip
    out["request_language_and_characters"] = case(
        "speech.request", "A language other than English adds `language_code`; the length is counted in characters (code points), not bytes; another model and key.",
        {"takes": value([take("进了！", "excited", language="zh", timings=True), take("\U0001f525 goal \U0001f525", "shout", model="eleven_v4", key="other-key"),
                         take("é\u00e9", "x"), take("a" * 500, "y"), take("Ünïcode", "a, b")])})  # fmt: skip
    out["alignment_kept"] = case(
        "speech.alignment", "The provider's character timings are kept as the characters joined and their start times; none, or none with times, is nothing.",
        {"answers": value([
            {"alignment": {"characters": ["[", "s", "]", " ", "H", "i"], "character_start_times_seconds": [0.0, 0.0, 0.05, 0.1, 0.4, 0.5], "character_end_times_seconds": [0.05, 0.05, 0.1, 0.4, 0.5, 0.6]}},
            {"alignment": {"characters": ["a"], "character_start_times_seconds": [0.0]}}, {"alignment": None}, {}, {"alignment": {}},
            {"alignment": {"characters": [], "character_start_times_seconds": []}}, {"alignment": {"characters": ["a"], "character_start_times_seconds": []}},
            {"alignment": {"characters": ["a", "b"]}}, {"alignment": {"characters": ["\U0001f525", "x"], "character_start_times_seconds": [0.1, 0.2]}},
        ])})  # fmt: skip

    def sig(*bursts, seconds=2.0, rate=24000):
        return {
            "rate": rate,
            "seconds": seconds,
            "bursts": [{"start_s": a, "dur_s": d, "amp": m} for a, d, m in bursts],
        }

    def meas(desc, recipe, times=(), window=0.15):
        return case(
            "audio.measure",
            desc,
            {"recipe": value(recipe), "times": value(list(times)), "window_s": value(window)},
            tol=0.0005,
        )

    out["measure_a_take_with_a_breath"] = meas(
        "0.3 s of silence, a quiet breath (-26 dB), the loud words, 0.2 s of silence: the trim starts at the breath (above -40 dB) and the tail keeps the breath out of the end only below -30 dB.",
        sig((0.3, 0.25, 600), (0.55, 1.0, 12000)),
        times=[0.3, 0.55, 0.6, 1.5, 1.8],
    )
    out["measure_a_very_quiet_breath"] = meas(
        "A breath at -35.6 dB: it is over the leading-silence level, so the trim starts there; the first strong sound is the words.",
        sig((0.3, 0.25, 200), (0.55, 1.0, 12000), seconds=2.0),
        times=[0.55, 0.5],
    )
    out["measure_a_breath_under_the_silence_level"] = meas(
        "A breath at -46 dB: under -40 dB, so the trim starts at the words.",
        sig((0.3, 0.25, 70), (0.55, 1.0, 12000)),
        times=[0.55],
    )
    out["measure_a_room_tail"] = meas(
        "A tail 0.6 s long at -34 dB after the words: under -30 dB, so the trim ends with the words (plus 20 ms).",
        sig((0.3, 1.0, 12000), (1.3, 0.6, 240), seconds=2.2),
        times=[0.3],
    )
    out["measure_a_silent_take"] = meas(
        "A take of silence: the trim is empty, there is no onset, a time is left as it is.",
        sig(seconds=1.0),
        times=[0.4],
    )
    out["measure_a_take_shorter_than_a_block"] = meas(
        "A take of 150 samples (shorter than a block of 10 ms at 24 kHz is 240): one block.",
        sig((0.0, 0.00625, 9000), seconds=0.00625),
        times=[0.0],
    )
    out["measure_two_words"] = meas(
        "Two words 0.5 s apart: a time is moved to the nearest rise within the window, else left as it is.",
        sig((0.2, 0.3, 11000), (0.8, 0.4, 11000), seconds=1.6),
        times=[0.2, 0.25, 0.35, 0.5, 0.8, 0.7, 0.95, 1.2],
        window=0.15,
    )
    out["measure_rates"] = meas(
        "The same words at 48 kHz (the rate of a recording) and 22.05 kHz.",
        sig((0.3, 0.8, 9000), seconds=1.5, rate=48000),
        times=[0.3, 0.35],
    )
    out["measure_rates_22k"] = meas(
        "22.05 kHz: a block is 220 samples.",
        sig((0.3, 0.8, 9000), seconds=1.5, rate=22050),
        times=[0.3, 0.35],
    )
    return out


def all_cases() -> dict[str, dict]:
    out = {}
    for make in (storyboard_cases, slots_cases, checks_cases, lexicon_cases, units_cases, cells_cases, cell_layout_cases, backend_cases, budget_cases, consent_cases, signals_cases, checks_more_cases, voice_cases, mix_cases, visuals_cases, spotter_cases, commentator_cases, intake_cases, speech_cases, compose_cases, flow_cases, card_cases, ui_cases, result_cases):  # fmt: skip
        for name, case in make().items():
            out[f"{case['area']}/{name}"] = case
    return out


def compose_cases() -> dict[str, dict]:
    """Added in T423: the loudness meter, the mix (S8) on drawn layers, what S10 draws and when, and where a take lands in the finished audio."""

    def case(function, desc, inputs, tol=None):
        c = {
            "area": "compose",
            "function": function,
            "language": None,
            "description": desc,
            "inputs": inputs,
        }
        if tol is not None:
            c["tolerance_s"] = tol
        return c

    def sig(seconds, *bursts, rate=48000):
        return {
            "rate": rate,
            "seconds": seconds,
            "bursts": [{"start_s": a, "dur_s": d, "amp": amp} for a, d, amp in bursts],
        }

    out = {}
    for name, desc, recipe, ch in [
        (
            "loudness_mono_steady",
            "EBU R128 integrated loudness and peak of steady noise, one channel.",
            sig(6, (0, 6, 8000)),
            1,
        ),
        (
            "loudness_stereo_steady",
            "The same noise on two identical channels is 3 LU louder: the meter adds the channels' power.",
            sig(6, (0, 6, 8000)),
            2,
        ),
        (
            "loudness_gated",
            "A loud part and a part 26 dB under it: the meter's gates leave the quiet part out of the integrated value.",
            sig(8, (0, 2, 12000), (3, 3, 600)),
            1,
        ),
        (
            "loudness_loud",
            "Noise near full scale: the peak is near 0 dBFS.",
            sig(4, (0, 4, 30000)),
            2,
        ),
        (
            "loudness_with_silence",
            "Noise with silence before, between and after (the absolute gate at -70 LUFS).",
            sig(10, (1, 2, 5000), (5, 2, 5000)),
            1,
        ),
    ]:
        out[name] = case(
            "audio.loudness", desc, {"recipe": value(recipe), "channels": value(ch)}, 0.1
        )

    def layer(kind, start, gain_db, recipe, loop=False, channels=1):
        d = {"kind": kind, "start": start, "gain_db": gain_db, "recipe": recipe}
        if loop:
            d["loop"] = True
        if (
            channels == 2
        ):  # the same noise on both channels: a stereo file, as the packs' sounds are
            d["channels"] = 2
        return d

    voice = [
        layer("voice", 1.0, 0.0, sig(1.5, (0, 1.5, 9000))),
        layer("voice", 4.0, 0.0, sig(1.5, (0, 1.5, 9000))),
    ]
    bed = layer("bed", 0.0, -18.0, sig(2, (0, 2, 6000)), loop=True)
    roar = layer("climax", 4.2, 0.0, sig(0.5, (0, 0.5, 20000)))
    sting = layer("sting_in", 0.0, 0.0, sig(0.8, (0, 0.8, 14000)), channels=2)
    windows = [[0.0, 0.9], [1.1, 2.4], [2.6, 2.7], [2.8, 2.9], [3.0, 3.9], [4.3, 4.95]]
    out["render_voice_bed_and_shots"] = case(
        "mix.render",
        "Two voice lines, a bed ducked under them (sidechain compressor, 0.02 / 8 / 20 ms / 350 ms), a one-shot and a sting, normalised to -14 LUFS: loudness, peak and the level in six windows, two of them just after the first line (the ducker letting go) (the PC's ducker stops the bed when the last voice line ends, so the bed after it is not compared).",
        {
            "layers": value([*voice, bed, roar, sting]),
            "duration_s": value(6.0),
            "windows": value(windows),
        },
        1.0,
    )
    out["render_bed_only"] = case(
        "mix.render",
        "A bed and a sting with no voice: nothing to duck under; brought to -14 LUFS (the gap between them is small enough for a linear gain: past 11 LU `loudnorm` turns dynamic and lifts the quiet part).",
        {
            "layers": value(
                [
                    layer("bed", 0.0, -8.0, sig(2, (0, 2, 6000)), loop=True),
                    layer("sting_in", 0.0, 0.0, sig(0.8, (0, 0.8, 5000))),
                ]
            ),
            "duration_s": value(6.0),
            "windows": value([[0.0, 0.7], [1.5, 5.0]]),
        },
        1.0,
    )
    out["render_voice_only"] = case(
        "mix.render",
        "One voice line alone, no bed: normalised, with silence before and after (several lines with no bed are left out of the cases: FFmpeg 8's `amix` sometimes ends such a mix early, see docs/provider-notes.md).",
        {
            "layers": value(voice[:1]),
            "duration_s": value(7.0),
            "windows": value([[0.0, 0.9], [1.1, 2.4], [2.8, 3.8], [4.1, 5.4]]),
        },
        1.0,
    )
    out["render_bed_loop_and_fades"] = case(
        "mix.render",
        "A bed shorter than the video, alone: looped to the length, faded in over 0.5 s and out over the last second, then brought to -14 LUFS.",
        {
            "layers": value([layer("bed", 0.0, -18.0, sig(1, (0, 1, 7000)), loop=True)]),
            "duration_s": value(3.5),
            "windows": value([[0.0, 0.25], [0.25, 0.5], [1.0, 2.0], [2.6, 2.7], [3.2, 3.4]]),
        },
        1.0,
    )

    base = {
        "storyboard": {"fixture": "sample_run/storyboard.json"},
        "script": {"fixture": "sample_run/script.json"},
    }
    times = [
        0.0,
        0.1,
        0.2,
        0.5,
        0.9,
        1.0,
        2.0,
        16.3,
        16.4,
        16.5,
        21.0,
        26.3,
        26.4,
        26.5,
        28.7,
        28.9,
    ]
    for style, pack_id in [
        ("football", "football_commentator"),
        ("nature", "nature_documentary"),
        ("news", "news_reporter"),
    ]:
        out[f"overlays_{style}"] = case(
            "compose.overlays",
            f"The overlays of the sample run in the {pack_id} pack: title, replay bug, end card (kicker over headline) and the AI label, each with its window, its fade and how much shows at sixteen times.",
            {**base, "pack": {"style": pack_id, "language": "en"}, "times": value(times)},
        )
    out["overlays_no_headline_no_end_card"] = case(
        "compose.overlays",
        "An empty headline leaves out the title and the headline of the end card; with the end card's window gone from the storyboard it is not drawn.",
        {
            "storyboard": {
                "fixture": "sample_run/storyboard.json",
                "merge_patch": {
                    "overlays": [
                        {"type": "ai_label", "start": 0.0, "end": 28.9},
                        {"type": "title", "start": 0.0, "end": 1.0},
                        {"type": "replay_bug", "start": 16.4, "end": 16.5},
                    ]
                },
            },
            "script": {"fixture": "sample_run/script.json", "merge_patch": {"headline": ""}},
            "pack": {"style": "football_commentator", "language": "en"},
            "times": value([0.0, 16.4, 16.45, 16.5, 28.0]),
        },
    )
    out["overlays_mandarin"] = case(
        "compose.overlays",
        "A Mandarin pack and headline: the same overlays; the words are the pack's.",
        {
            "storyboard": {"fixture": "sample_run/storyboard.json"},
            "script": {
                "fixture": "sample_run/script.json",
                "merge_patch": {"headline": "任意球直挂死角"},
            },
            "pack": {"style": "football_commentator", "language": "zh"},
            "times": value(times),
        },
    )

    background = sig(10, (0, 10, 3000))
    take = sig(1.5, (0, 1.5, 12000))
    for name, desc, at, planned in [
        (
            "landed_on_plan",
            "A take added into noise exactly where it was planned: found there.",
            3.0,
            3.0,
        ),
        (
            "landed_late",
            "A take 0.2 s after its plan (a shift from joining or encoding): found at its real start.",
            3.2,
            3.0,
        ),
        ("landed_early", "A take 0.3 s before its plan: found at its real start.", 2.7, 3.0),
        (
            "landed_outside_the_search",
            "A take 0.8 s after its plan, past the 0.5 s search: the nearest match inside the search.",
            3.8,
            3.0,
        ),
        (
            "landed_near_the_end",
            "A plan so near the end of the output that the take does not fit: the plan itself.",
            9.5,
            9.5,
        ),
    ]:
        out[name] = case(
            "compose.landed",
            desc,
            {
                "output": value(background),
                "take": value(take),
                "offset_s": value(at),
                "planned_start_s": value(planned),
            },
            0.0005,
        )
    return out


def flow_cases() -> dict[str, dict]:
    """Added in T424: what the flow does with a run folder and on the next start (flow/runner.py), the real `Run` driven with scripted stages."""

    def case(desc, rounds, present=(), seed_held=False, uc=None, ps_patch=None, ps=True):
        ucref = {"fixture": "sample_run/user_context.json"}
        if uc is not None:
            ucref["merge_patch"] = uc
        psref = {"fixture": "sample_run/play_sheet.json"}
        if ps_patch is not None:
            psref["merge_patch"] = ps_patch
        return {
            "area": "flow", "function": "flow.run", "language": None, "description": desc,
            "inputs": {"user_context": ucref, "play_sheet": psref if ps else value(None), "present": value(list(present)),
                       "seed_held": value(seed_held), "rounds": value(rounds)},
        }  # fmt: skip

    unconfirmed = {"confirmed_by_user": False}
    fixture_files = [
        "clip_meta.json",
        "work.mp4",
        "play_sheet.json",
        "storyboard.json",
        "script.json",
    ]
    confirm = {"set_play_sheet": {"confirmed_by_user": True}}
    out = {}
    out["from_a_clip_to_done"] = case(
        "A clip goes through S0 to S3 and stops at the play card (`waiting_play_card`); the card answered, the next start goes on from S4 and finishes. Each stage is recorded once, with an event for starting and for finishing.",
        [{}, confirm], ps_patch=unconfirmed)  # fmt: skip
    out["a_fixture_seeds_what_it_holds"] = case(
        "A folder holding the outputs of S0, S3 (and so S1, S2), the card, S4 and S5 records them as seeded and runs S9, S6, S7, S8 and S10.",
        [{}],
        present=fixture_files,
        seed_held=True,
    )
    out["a_failed_stage_runs_again_on_the_next_start"] = case(
        "S7 fails: the run ends `failed` with its note; the next start does not repeat the stages that were done and tries S7 again (a stage that was tried and failed runs again whatever it left behind).",
        [{"script": {"S7": {"status": "failed", "note": "The voice could not be made"}}}, {}],
        present=fixture_files,
        seed_held=True,
    )
    out["degraded_goes_on"] = case(
        "A stage that ends `degraded` lets the flow go on, and is recorded as degraded with its note.",
        [
            {
                "script": {
                    "S6": {"status": "degraded", "note": "one line rewritten"},
                    "S8": {"status": "degraded", "note": "measured -12.9 LUFS"},
                }
            }
        ],
        present=fixture_files,
        seed_held=True,
    )
    out["a_stopped_stage_ends_the_run"] = case(
        "S9 stops: the run ends `stopped` with the note, nothing after S9 runs, and the next start tries S9 again.",
        [{"script": {"S9": {"status": "stopped", "note": "cancelled"}}}, {}],
        present=fixture_files,
        seed_held=True,
    )
    out["a_stage_that_raises_fails_the_run_with_its_reason"] = case(
        "A stage that raises an exception ends the run `failed`, the note `RuntimeError: boom`.",
        [{"script": {"S10": {"raises": "boom"}}}],
        present=fixture_files,
        seed_held=True,
    )
    out["killed_before_a_stage_wrote_anything"] = case(
        "The process dies in S8 before it wrote: no record for S8 and no output; the next start runs S8 and S10.",
        [{"script": {"S8": {"crash": "before"}}}, {}],
        present=fixture_files,
        seed_held=True,
    )
    out["killed_after_a_stage_wrote_its_outputs"] = case(
        "The process dies in S8 after it wrote its outputs and before it was recorded: the next start records S8 as seeded (its output was already there) and runs S10.",
        [{"script": {"S8": {"crash": "after"}}}, {}],
        present=fixture_files,
        seed_held=True,
    )
    out["consent_is_checked_before_s2"] = case(
        "A run without consent runs S0 and S1 and stops at S2 with its note; the stage is recorded `stopped`.",
        [{}],
        uc={"consent": None},
        ps_patch=unconfirmed,
    )
    out["consent_of_an_old_text_is_not_consent"] = case(
        "A consent given to an earlier text version stops the run at S2.",
        [{}],
        uc={"consent": {"text_version": "3"}},
        ps_patch=unconfirmed,
    )
    out["a_flagged_clip_stops_after_the_card"] = case(
        "A play sheet flagged sensitive stops the run at the first stage after the card, whatever path led there; the note names the reason.",
        [{}],
        present=fixture_files,
        seed_held=True,
        ps_patch={"sensitive": {"flag": True, "reason": "someone may be hurt"}},
    )
    out["a_clip_nobody_checked_stops_after_the_card"] = case(
        "A play sheet whose sensitivity could not be checked, and which nobody answered for, stops the run after the card.",
        [{}],
        present=fixture_files,
        seed_held=True,
        ps_patch={"sensitive": {"flag": False, "reason": None, "checked": False}},
    )
    out["rerun_from_a_stage"] = case(
        "A finished run re-run from S5: the outputs of S5 and the stages after it are deleted and forgotten, S0 to S9 are kept; the next start runs S5, S6, S7, S8 and S10.",
        [{}, {"rerun": "S5"}],
        present=fixture_files,
        seed_held=True,
    )
    return out


def card_cases() -> dict[str, dict]:
    """Added in T425: what the play card does with the user's answers (flow/runner.py `answer`, `to_check`, `nearest_event`, `rest_cost_usd`)."""

    def sheet(change=None):
        """The sample play sheet as an input; `change` edits a copy (a merge patch cannot reach into an array)."""
        ps = json.loads((paths.SAMPLE_RUN / "play_sheet.json").read_text())
        if change:
            change(ps)
        return value(ps)

    def event(ps, eid):
        return next(e for e in ps["events"] if e["id"] == eid)

    def base(frame=313, edits=None, context=None, generic=False, change=None, desc=""):
        psref = sheet(change)
        ucref = {"fixture": "sample_run/user_context.json"}
        return {
            "area": "card", "function": "card.answer", "language": None, "description": desc,
            "inputs": {"play_sheet": psref, "user_context": ucref, "clip_meta": {"fixture": "sample_run/clip_meta.json"}, "frame": value(frame),
                       "edits": value(edits or {}), "context": value(context or {}), "generic": value(generic)},
        }  # fmt: skip

    def unsure(ps):  # e5 is a model event below the highlight line: an item to check
        event(ps, "e5")["confidence"] = 0.5

    out = {}
    out["the_key_moment_only"] = base(
        289,
        desc="The user picks a candidate: the climax takes its frame and its time (frame over fps), the method becomes `user`, the confidence 1.0 and the sheet is confirmed.",
    )
    out["a_frame_past_the_clip_is_refused"] = base(
        450,
        desc="A frame after the last of the clip (450 frames at 30 a second: 0 to 449) is refused with the range, and nothing is written.",
    )
    out["a_negative_frame_is_refused"] = base(
        -1, desc="A frame before the first is refused the same way."
    )
    out["keep_and_remove_events"] = base(edits={"events": [{"id": "e5", "keep": True}, {"id": "e2", "keep": False}]}, change=unsure,
        desc="`keep: true` makes an event the user's (`source: user`); `keep: false` removes it; the others stay as they were.")  # fmt: skip
    out["removing_the_key_moments_event_moves_it_to_the_nearest"] = base(frame=300, edits={"events": [{"id": "e6", "keep": False}]},
        desc="The key moment's own event is removed: the climax's `event_id` becomes the nearest kept event to the moment (an event that holds it first, then the later start).")  # fmt: skip
    out["removing_every_event_is_refused"] = base(
        edits={"events": [{"id": f"e{i}", "keep": False} for i in range(1, 9)]},
        desc="At least one event must be kept.",
    )
    out["an_unknown_event_is_refused"] = base(
        edits={"events": [{"id": "e99", "keep": True}]},
        desc="Each event edit is {id, keep} with an event of this play sheet.",
    )
    out["a_keep_that_is_not_a_boolean_is_refused"] = base(
        edits={"events": [{"id": "e1", "keep": "yes"}]}, desc="`keep` is true or false."
    )
    out["the_outcome_sentence_is_the_key_events_outcome_too"] = base(edits={"outcome_summary": "  Scored from the free kick.  "},
        desc="The outcome sentence is trimmed, marked the user's with confidence 1.0, and is also the outcome of the key moment's event.")  # fmt: skip
    out["the_activity_is_edited"] = base(
        edits={"activity": "football set piece"},
        desc="The activity is a sentence of 1 to 80 characters, marked the user's.",
    )
    out["an_activity_over_80_characters_is_refused"] = base(
        edits={"activity": "x" * 81},
        desc="Longer than 80 characters: refused, naming the field and the limit.",
    )
    out["an_empty_outcome_is_refused"] = base(
        edits={"outcome_summary": "   "}, desc="An empty sentence is refused (1 to 160 characters)."
    )
    out["the_followed_actor_changes"] = base(
        edits={"followed_actor": "a2"},
        desc="The followed actor is another actor of the sheet: `followed` moves to it.",
    )
    out["an_unknown_actor_is_refused"] = base(
        edits={"followed_actor": "a9"}, desc="An actor that is not in the sheet is refused."
    )
    out["the_players_name_goes_to_the_followed_actor"] = base(context={"player_name": "Lee", "score": " 2-1 "},
        desc="Context fields go into the user context, trimmed; a name also becomes the followed actor's `user_name`; an empty one is null.")  # fmt: skip
    out["context_of_another_kind_is_refused"] = base(
        context={"favourite_colour": "red"},
        desc="Only the player, the two teams and the score are taken.",
    )
    out["a_context_field_over_40_characters_is_refused"] = base(
        context={"team_home": "y" * 41}, desc="At most 40 characters each."
    )
    out["not_at_all_makes_a_generic_play_sheet"] = base(
        generic=True,
        desc="`Not at all` then `Narrate without saying what happened`: the sheet keeps what the model saw and is marked `mode: generic`.",
    )
    out["an_answer_that_fails_writes_nothing_of_the_edits_before_it"] = base(edits={"activity": "fine", "followed_actor": "a9"},
        desc="A refused edit leaves everything as it was, the valid edit before it too (the function returns only the error).")  # fmt: skip

    def items(desc, change=None):
        return {
            "area": "card",
            "function": "card.items",
            "language": None,
            "description": desc,
            "inputs": {"play_sheet": sheet(change)},
        }

    def two_unsure(ps):
        event(ps, "e5")["confidence"] = 0.5
        event(ps, "e2")["confidence"] = 0.69

    def between(ps):
        ps["climax"]["t"] = 9.65
        event(ps, "e3").update(t_start=8.2, t_end=9.6)
        event(ps, "e4").update(t_start=9.7, t_end=9.7)

    out["items_to_check"] = items(
        "The events the card asks about are the model's and below 0.7 (the user's are not asked about again); the key moment's event is the one that holds the moment.",
        two_unsure,
    )
    out["items_when_nothing_is_unsure"] = items(
        "With every model event at 0.7 or over, nothing is asked."
    )
    out["items_the_nearest_event_to_a_moment_between_events"] = items(
        "A moment that no event holds: the nearest event (by distance), the later start on a tie.",
        between,
    )

    def cost(desc, style, language):
        return {"area": "card", "function": "card.rest_cost", "language": language, "description": desc,
                "inputs": {"clip_meta": {"fixture": "sample_run/clip_meta.json"}, "pack": {"style": style, "language": language}}}  # fmt: skip

    out["rest_cost_english"] = cost(
        "The cost of the rest of the run: the Commentator's ceiling plus the voice for as many words as the clip's length holds at the pack's rate (6.5 characters a word at 0.10 USD per 1,000).",
        "football_commentator",
        "en",
    )
    out["rest_cost_mandarin"] = cost(
        "In Mandarin a unit is a character: one character each.", "football_commentator", "zh"
    )
    out["rest_cost_of_another_pack"] = cost(
        "The voice's words a second differ by pack.", "nature_documentary", "en"
    )
    return out


def ui_cases() -> dict[str, dict]:
    """Added in T425: what the progress screen shows for a manifest (web/app.py `state`): the eight steps, the time left, the message of a stopped run."""

    def stage(name, status="ok", duration=1.0, cost=0.0, note=""):
        return {
            "stage": name,
            "status": status,
            "started_at": "2026-11-22T10:00:00Z",
            "duration_s": duration,
            "cost_usd": cost,
            "note": note,
        }

    def manifest(stages, status="running", total=None):
        return {
            "run_id": "r_test",
            "created_at": "2026-11-22T10:00:00Z",
            "status": status,
            "style": "football_commentator",
            "language": "en",
            "stages": stages,
            "total_usd": total,
        }

    typical = {
        "S0": 2.0,
        "S1": 3.0,
        "S2": 10.0,
        "S3": 6.0,
        "S4": 1.0,
        "S9": 14.0,
        "S5": 12.0,
        "S6": 1.0,
        "S7": 5.0,
        "S8": 3.0,
        "S10": 9.0,
    }

    def case(desc, m, files=("clip_meta.json",), typ=None):
        return {"area": "card", "function": "ui.state", "language": None, "description": desc,
                "inputs": {"manifest": value(m), "typical": value(typical if typ is None else typ), "files": value(list(files))}}  # fmt: skip

    ok = lambda *names: [stage(n, duration=2.0) for n in names]  # noqa: E731
    out = {}
    out["state_a_run_just_started"] = case(
        "A run with nothing done: every step waits, step 1 of 8, the whole of the typical time left (about a minute).",
        manifest([]),
        files=(),
    )
    out["state_the_first_stages_done"] = case(
        "S0 and S1 done: step 1 is done with its seconds (the two summed and rounded), step 2 runs.",
        manifest(ok("S0", "S1")),
    )
    out["state_at_the_play_card"] = case(
        "At the card the run waits for the user: step 4 is `waiting_user`, the three before it are done.",
        manifest(
            [
                *ok("S0", "S1", "S2", "S3"),
                stage("A", "waiting", 0.0, note="waiting for the play card"),
            ],
            status="waiting_play_card",
        ),
    )
    out["state_the_card_answered"] = case(
        "After the answer step 4 is done (its name changes to what the user confirmed) and shows no seconds; step 5 has begun.",
        manifest([*ok("S0", "S1", "S2", "S3"), stage("A", "ok", 0.0), stage("S4", duration=1.0)]),
    )
    out["state_a_degraded_stage_counts_as_done"] = case(
        "A degraded stage (a fallback was used) is done; the step still shows its seconds.",
        manifest(
            [
                *ok("S0", "S1", "S2", "S3", "A", "S4", "S9"),
                stage("S5", "degraded", 9.0, cost=0.0042, note="generic lines for l2"),
            ]
        ),
    )
    out["state_seeded_stages_are_done"] = case(
        "Seeded stages (a fixture, or an output that was already there) are done.",
        manifest(
            [
                stage("S0", "seeded", 0.0),
                stage("S1", "seeded", 0.0),
                stage("S2", "seeded", 0.0),
                stage("S3", "seeded", 0.0),
                stage("A", "seeded", 0.0),
            ]
        ),
    )
    out["state_done"] = case(
        "A finished run: all eight steps done, step 8 of 8, no time left (a few seconds), the video and the clip present, the cost the manifest's total.",
        manifest(
            ok("S0", "S1", "S2", "S3", "A", "S4", "S9", "S5", "S6", "S7", "S8", "S10"),
            status="done",
            total=0.0412,
        ),
        files=("clip_meta.json", "final.mp4"),
    )
    out["state_time_left_wordings"] = case(
        "The time left is the sum of the typical durations of the stages not done, read in three wordings: under 10 s, under 45 s, else.",
        manifest([*ok("S0", "S1", "S2", "S3", "A", "S4", "S9", "S5", "S6", "S7", "S8")]),
        typ={"S10": 8.0},
    )
    out["state_time_left_half_a_minute"] = case(
        "Thirty seconds of typical work left: about half a minute.",
        manifest([*ok("S0", "S1", "S2", "S3", "A", "S4", "S9")]),
        typ={"S5": 12.0, "S6": 1.0, "S7": 5.0, "S8": 3.0, "S10": 9.0},
    )
    out["state_failed_names_the_step"] = case(
        "A failed stage: the step is `failed`, and the message names the step in lower case, never the stage's own note.",
        manifest(
            [
                *ok("S0", "S1", "S2", "S3", "A", "S4"),
                stage("S9", "failed", 3.0, note="Invalid Nal units from the muxer"),
            ],
            status="failed",
        ),
    )
    out["state_stopped_for_a_sensitive_clip"] = case(
        "A clip flagged at S3 stops the run with the reason, without its final full stop.",
        manifest(
            [
                *ok("S0", "S1", "S2"),
                stage("S3", "stopped", 1.0, note="sensitive: someone may be hurt or upset."),
            ],
            status="stopped",
        ),
    )
    out["state_stopped_by_the_cap"] = case(
        "A call that would go over a cap stops the run with the limit message.",
        manifest(
            [
                *ok("S0", "S1", "S2", "S3", "A", "S4"),
                stage("S5", "stopped", 1.0, note="the run cap would be exceeded"),
            ],
            status="stopped",
        ),
    )
    out["state_stopped_at_the_card"] = case(
        "`Stop here` at the card: its own message.",
        manifest(
            [
                *ok("S0", "S1", "S2", "S3"),
                stage("A", "stopped", 0.0, note="stopped at the play card"),
            ],
            status="stopped",
        ),
    )
    out["state_an_upload_refused_says_why"] = case(
        "S0's note is written for the user: the reason the clip was refused is the message.",
        manifest(
            [
                stage(
                    "S0",
                    "failed",
                    0.1,
                    note="That clip is 42 seconds long. We need 3 to 30 seconds.",
                )
            ],
            status="failed",
        ),
        files=(),
    )
    out["state_the_cost_is_the_sum_when_there_is_no_total"] = case(
        "With no total in the manifest the cost is the sum of the stages' costs.",
        manifest([stage("S2", cost=0.0123), stage("S5", cost=0.0301)]),
        files=(),
    )
    out["state_a_zero_total_is_not_a_total"] = case(
        "A manifest total of 0 (a run that was cut off before its total was written) falls back to the sum of the stages' costs, as `total or sum`.",
        manifest([stage("S2", cost=0.0111), stage("S5", cost=0.0222)], total=0.0),
        files=(),
    )
    return out


def result_cases() -> dict[str, dict]:
    """Added in T426: the result's script panel and its time and cost panel (web/views.py)."""
    sample = lambda name: {"fixture": f"sample_run/{name}"}  # noqa: E731
    voice = {"fixture": "voice/sample_run_voice.json"}

    def panel(desc, script, voice_ref, checks, gloss=None, lang=None):
        return {"area": "card", "function": "result.script_panel", "language": lang, "description": desc,
                "inputs": {"script": script, "voice": voice_ref, "checks": {"value": checks}, "gloss": {"value": gloss}}}  # fmt: skip

    def money(desc, steps, cost):
        rows = [{"name": n, "duration_s": d, "cost_usd": c} for n, d, c in steps]
        return {"area": "card", "function": "result.time_and_cost", "language": None, "description": desc,
                "inputs": {"state": {"value": {"steps": rows, "cost_usd": cost}}}}  # fmt: skip

    out = {}
    out["result_script_panel_sample_run"] = panel(
        "The sample run's seven lines with the time each is spoken (one decimal and ' s · '), the two key lines marked, the climax line named, every check passed.",
        sample("script.json"), voice, {"passed": True, "failures": []},
    )  # fmt: skip
    out["result_script_panel_a_dropped_line_has_no_time"] = panel(
        "A line that was not voiced shows no time and is marked dropped; the checks' failures are listed as 'where: detail' and the panel is not passed.",
        sample("script.json"),
        {"value": {"lines": [{"id": "l1", "timeline_start": 1.3, "dropped": None}, {"id": "l2", "timeline_start": 5.04, "dropped": "did not fit"}, {"id": "l3", "timeline_start": 11.4, "dropped": None}]}},
        {"passed": False, "failures": [{"where": "l2", "detail": "names a player the user did not give"}]},
    )  # fmt: skip
    out["result_script_panel_no_voice_yet"] = panel(
        "With no voice file the lines have no time and none is dropped; with no checks file the panel is passed.",
        sample("script.json"), {"value": None}, None,
    )  # fmt: skip
    out["result_script_panel_failures_alone_fail_it"] = panel(
        "A failure in the list fails the panel even when `passed` says true.",
        sample("script.json"), voice, {"passed": True, "failures": [{"detail": "too long"}]},
    )  # fmt: skip
    out["result_script_panel_mandarin_with_gloss"] = panel(
        "A Mandarin script is marked `zh-Hans` and carries the English gloss of each line it has one for.",
        {"value": {"language": "zh", "lines": [{"id": "l1", "role": "opening", "text": "任意球！"}, {"id": "l2", "role": "replay_climax", "text": "进了！"}, {"id": "l3", "role": "made_up_role", "text": "好"}]}},
        {"value": {"lines": [{"id": "l1", "timeline_start": 2.0}, {"id": "l2", "timeline_start": 12.25}]}},
        {"passed": True, "failures": []}, {"l1": "Free kick!", "l2": "In!"}, "zh",
    )  # fmt: skip
    out["result_time_and_cost"] = money(
        "Each step's seconds ('5 s', blank when it has none) and cost to four places, the total seconds as a sum of the whole seconds and the total cost.",
        [("Checking your clip", 3, 0.0), ("Finding the moment", 41, 0.0123), ("You confirmed what happened", None, 0.0), ("Writing the commentary", 12, 0.0301), ("Making your video", 9, 0.0)],
        0.0424,
    )  # fmt: skip
    out["result_time_and_cost_rounding"] = money(
        "Costs round to four places: 0.00005 and 0.123456.",
        [("a", 1, 0.00005), ("b", 2, 0.123456)],
        0.123506,
    )  # fmt: skip
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
