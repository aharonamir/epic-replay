"""Command line: `python -m epic_replay.flow <command>`. The runner itself comes in T027."""

from __future__ import annotations

import argparse
import logging

from epic_replay.styles import loader


def styles() -> None:
    for pid, pack in loader.load().items():
        mark = "*" if pack["default"] else " "
        print(f"{mark} {pid:24} {pack['name']}: {pack['description']}")


def main() -> None:
    logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(message)s")
    ap = argparse.ArgumentParser(prog="python -m epic_replay.flow")
    sub = ap.add_subparsers(dest="command", required=True)
    sub.add_parser("styles", help="list the style packs that load; refused ones are logged")
    args = ap.parse_args()
    {"styles": styles}[args.command]()


if __name__ == "__main__":
    main()
