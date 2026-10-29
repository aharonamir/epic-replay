"""Command line: `python -m epic_replay.flow <command>`.

    python -m epic_replay.flow styles
    python -m epic_replay.flow run CLIP --consent [--style ID] [--name NAME] [--mode MODE]
    python -m epic_replay.flow run --fixture tests/fixtures/sample_run --mode replay

`run CLIP` makes a new run folder under EPIC_RUNS_DIR and runs S0 to S10 (T027); the key
moment is picked at the prompt. `--consent` records the consent wording, which is printed
first. `--pick` answers the play card without asking (`proposed`, `c1`.., or a frame).
`--mode` sets EPIC_PROVIDER_MODE for this run (live, record or replay). `--card page` stops at
the play card instead, for the page to answer (T014): open the address printed and confirm
there, with the app running (`uvicorn epic_replay.web.app:app --port 8000`).
"""

from __future__ import annotations

import argparse
import logging
import os
import sys
from pathlib import Path


def styles() -> None:
    from epic_replay.styles import loader

    for pid, pack in loader.load().items():
        mark = "*" if pack["default"] else " "
        print(f"{mark} {pid:24} {pack['name']}: {pack['description']}")


def run(args: argparse.Namespace) -> int:
    if args.mode:
        os.environ["EPIC_PROVIDER_MODE"] = args.mode
    from epic_replay import config
    from epic_replay.flow import runner
    from epic_replay.guards import consent
    from epic_replay.styles import loader

    s = config.load()
    if args.card == "page" and args.pick is not None:
        print("--pick answers at the prompt; with --card page the page answers", file=sys.stderr)
        return 2
    ask = None if args.card == "page" else input
    if args.pick is not None:
        answer = "" if args.pick == "proposed" else args.pick
        ask = lambda prompt: print(prompt + answer) or answer  # noqa: E731
    if args.fixture:
        d, seeded = runner.seed(Path(args.fixture), s.runs_dir, args.run_id or "fixture")
        print(f"run {d} (mode {s.provider_mode}); seeded: {', '.join(sorted(seeded)) or 'none'}")
        status = runner.Run(d, s, ask=ask).run(seeded)
    else:
        if not args.clip:
            print("give a clip, or --fixture", file=sys.stderr)
            return 2
        print(consent.CONSENT_TEXT)
        print(consent.provider_line(s))
        if not args.consent:
            print("Add --consent to confirm the wording above.", file=sys.stderr)
            return 2
        pack = loader.load()[args.style] if args.style else loader.default(loader.load())
        uc = {
            "consent": consent.record(True),
            "keep_for_demo": False,
            "style": pack["id"],
            "activity": None,
            "player_name": args.name,
            "team_home": None, "team_away": None, "score": None,
            "keep_original_audio": not args.mute,
            "express": True,
            "description": None,
        }  # fmt: skip
        d = runner.new_run(Path(args.clip), s.runs_dir, uc, args.run_id)
        print(f"run {d} (mode {s.provider_mode}, style {pack['id']})")
        status = runner.Run(d, s, ask=ask).run()
    print(f"status: {status}")
    if status == "done":
        print(f"video: {d / 'final.mp4'}")
    if status == "waiting_play_card":
        print(f"play card: http://localhost:8000/r/{d.name}")
    return 0 if status in ("done", "waiting_play_card") else 1


def main() -> None:
    logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(message)s")
    ap = argparse.ArgumentParser(prog="python -m epic_replay.flow")
    sub = ap.add_subparsers(dest="command", required=True)
    sub.add_parser("styles", help="list the style packs that load; refused ones are logged")
    r = sub.add_parser("run", help="run a clip, or a fixture, from S0 to S10")
    r.add_argument("clip", nargs="?")
    r.add_argument("--fixture")
    r.add_argument("--mode", choices=("live", "record", "replay"))
    r.add_argument("--consent", action="store_true")
    r.add_argument("--style")
    r.add_argument("--name", help="the player's name, if the user gives one")
    r.add_argument("--mute", action="store_true", help="mute the clip's own sound")
    r.add_argument("--pick", help="answer the play card: proposed, c1.., or a frame number")
    r.add_argument("--run-id")
    r.add_argument(
        "--card",
        choices=("prompt", "page"),
        default="prompt",
        help="answer the play card at the prompt, or on the page",
    )
    args = ap.parse_args()
    if args.command == "styles":
        styles()
    else:
        sys.exit(run(args))


if __name__ == "__main__":
    main()
