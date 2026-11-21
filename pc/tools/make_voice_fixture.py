"""`shared/fixtures/voice/sample_run_voice.json`: S7's output for the sample run, made by replaying the PC's recorded takes (spec 004, T422).

    python tools/make_voice_fixture.py          # from pc/

The seven takes of the sample script are recorded in `pc/tests/fixtures/recordings/speech/` (the JSON and its WAV, found by the recording key). S7 runs on
them in replay mode, so nothing is sent and nothing is paid; the result is what the Android port of S7 must place the same way.
"""

from __future__ import annotations

import shutil
import tempfile
from dataclasses import replace
from pathlib import Path

from epic_replay import config, paths
from epic_replay.guards.budget import Ledger
from epic_replay.providers.replay import Recorder
from epic_replay.providers.speech import SpeechAdapter
from epic_replay.stages import s7_voice
from epic_replay.stages.base import RunContext


def main() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp)
        for name in ("script.json", "storyboard.json"):
            shutil.copy(paths.SAMPLE_RUN / name, d / name)
        s = replace(config.load(), provider_mode="replay")
        ledger = Ledger(d, s, day_file=d / "day.json")
        recordings = paths.ROOT / "pc/tests/fixtures/recordings"
        speech = SpeechAdapter(
            ledger, out_dir=d / "voice/raw", settings=s, recorder=Recorder("replay", recordings)
        )
        res = s7_voice.run(
            RunContext(
                run_dir=d,
                settings=s,
                adapters={"speech": speech},
                ledger=ledger,
                emit=lambda e: None,
            )
        )
        if res.status != "ok":
            raise SystemExit(f"S7 gave {res.status}: {res.note}")
        out = (
            paths.FIXTURES / "voice" / "sample_run_voice.json"
        )  # not in sample_run/: a file there seeds S7 as done in the fixture runs
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text((d / "voice.json").read_text())
    print("wrote", out)


if __name__ == "__main__":
    main()
