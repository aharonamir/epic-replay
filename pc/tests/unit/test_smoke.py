import shutil
import subprocess


def test_ffmpeg_available():
    assert shutil.which("ffmpeg") and shutil.which("ffprobe")
    out = subprocess.run(["ffmpeg", "-version"], capture_output=True, text=True).stdout
    assert out.startswith("ffmpeg version")


def test_package_imports():
    import epic_replay  # noqa: F401
