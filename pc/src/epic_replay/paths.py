"""Where everything lives (specs/002-platform-split, FR-008).

The one module that finds the repository from its own location. Every other module and test
takes its folders from here; files inside a package (the page templates, the graphics skins)
are still found next to their module.

`EPIC_SHARED_DIR` overrides the shared data folder, `EPIC_RUNS_DIR` the runs folder and
`EPIC_DEMO_DIR` the cached demo runs (FR-009). A folder that is needed and missing raises
`MissingFolder`, naming the folder and where it was looked for (FR-010).
"""

from __future__ import annotations

import os
from pathlib import Path

from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parents[3]
PC = ROOT / "pc"

# The PC app's settings file, read before any override below (FR-009).
ENV_FILE = PC / ".env"
load_dotenv(ENV_FILE, override=False)

# Shared data: both apps read it (FR-002).
SHARED = Path(os.environ.get("EPIC_SHARED_DIR") or ROOT / "shared")
CONTRACTS = SHARED / "contracts"
EXAMPLES = CONTRACTS / "examples"
STYLES = SHARED / "styles"
LEXICONS = SHARED / "lexicons"
ASSETS = SHARED / "assets"
FONTS = ASSETS / "fonts"
FIXTURES = SHARED / "fixtures"
SAMPLE_RUN = FIXTURES / "sample_run"
PLANTED = FIXTURES / "planted_errors"
ZH_FIXTURES = FIXTURES / "zh"
CONFORMANCE = SHARED / "conformance"

# The PC app's own files (FR-003).
RECORDINGS = PC / "tests" / "fixtures" / "recordings"
GOLDEN = PC / "tests" / "fixtures" / "english_golden.json"
TOOLS = PC / "tools"

# Evaluation labels are shared; the clips are local and not tracked (FR-006).
EVAL = ROOT / "eval"
LABELS = EVAL / "labels"
CLIPS = EVAL / "clips"

# Local folders, not tracked.
RUNS_DEFAULT = PC / "runs"
RUNS = Path(os.environ.get("EPIC_RUNS_DIR") or RUNS_DEFAULT)
DEMO = Path(os.environ.get("EPIC_DEMO_DIR") or PC / "demo")


class MissingFolder(FileNotFoundError):
    pass


def require(path: Path, what: str) -> Path:
    """`path` if it is a folder; otherwise MissingFolder naming `what` and the path."""
    if not path.is_dir():
        raise MissingFolder(f"the {what} folder was not found at {path}; see docs/migration-002.md")
    return path
