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
    def case(desc, ps, meta, expected=None):
        return {"area": "storyboard", "function": "storyboard.build", "language": None,
                "description": desc, "tolerance_s": 0.001,
                "inputs": {"play_sheet": ps, "clip_meta": meta, "min_output_s": value(20.0)},
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
        "zh_text_read_as_en": case("Text read with the English rule counts tokens, whatever the script.",
                                   "en", ["好球！ 漂亮", "他在 2026 年进球。"]),
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


def all_cases() -> dict[str, dict]:
    out = {}
    for make in (storyboard_cases, slots_cases, checks_cases, lexicon_cases, units_cases, cells_cases):  # fmt: skip
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
