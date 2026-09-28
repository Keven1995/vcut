import subprocess
from pathlib import Path


class FFmpegVideoProcessor:
    def __init__(self, binary: str = "ffmpeg") -> None:
        if not binary:
            raise ValueError("binary must not be empty")
        self._binary = binary

    def extract_audio(self, source: Path, destination: Path) -> None:
        self._run(
            [
                self._binary,
                "-y",
                "-i",
                str(source),
                "-vn",
                "-acodec",
                "pcm_s16le",
                str(destination),
            ]
        )

    def cut(self, source: Path, destination: Path, start_seconds: float, end_seconds: float) -> None:
        if start_seconds < 0 or end_seconds <= start_seconds:
            raise ValueError("end_seconds must be greater than start_seconds")
        self._run(
            [
                self._binary,
                "-y",
                "-ss",
                str(start_seconds),
                "-to",
                str(end_seconds),
                "-i",
                str(source),
                "-c",
                "copy",
                str(destination),
            ]
        )

    def _run(self, command: list[str]) -> None:
        subprocess.run(command, check=True, capture_output=True, text=True)
