import json
import subprocess
from pathlib import Path

from vcut_workers.domain.media import VideoMetadata


class FFmpegVideoProcessor:
    def __init__(self, binary: str = "ffmpeg", probe_binary: str = "ffprobe") -> None:
        if not binary:
            raise ValueError("binary must not be empty")
        if not probe_binary:
            raise ValueError("probe_binary must not be empty")
        self._binary = binary
        self._probe_binary = probe_binary

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

    def cut(
        self, source: Path, destination: Path, start_seconds: float, end_seconds: float
    ) -> None:
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

    def probe(self, source: Path) -> VideoMetadata:
        completed = subprocess.run(
            [
                self._probe_binary,
                "-v",
                "error",
                "-show_entries",
                "format=format_name,duration:stream=codec_type,codec_name,width,height,avg_frame_rate",
                "-of",
                "json",
                str(source),
            ],
            check=True,
            capture_output=True,
            text=True,
        )
        payload = json.loads(completed.stdout)
        streams = payload.get("streams", [])
        video_stream = next(
            (stream for stream in streams if stream.get("codec_type") == "video"), None
        )
        if video_stream is None:
            raise ValueError("media does not contain a video stream")
        audio_stream = next(
            (stream for stream in streams if stream.get("codec_type") == "audio"), None
        )
        format_payload = payload.get("format", {})
        format_name = _normalize_container(str(format_payload.get("format_name", "")))
        return VideoMetadata(
            container=format_name,
            duration_seconds=float(format_payload.get("duration", 0)),
            width=int(video_stream.get("width", 0)),
            height=int(video_stream.get("height", 0)),
            frame_rate=_parse_frame_rate(video_stream.get("avg_frame_rate")),
            has_audio=audio_stream is not None,
            video_codec=str(video_stream.get("codec_name", "")),
            audio_codec=None if audio_stream is None else str(audio_stream.get("codec_name", "")),
        )

    def _run(self, command: list[str]) -> None:
        subprocess.run(command, check=True, capture_output=True, text=True)


def _parse_frame_rate(value: object) -> float | None:
    if not isinstance(value, str) or not value or value in {"0/0", "N/A"}:
        return None
    numerator, denominator = value.split("/", 1)
    if float(denominator) == 0:
        return None
    return float(numerator) / float(denominator)


def _normalize_container(value: str) -> str:
    names = {part.strip().lower() for part in value.split(",")}
    if "mp4" in names:
        return "mp4"
    if "webm" in names:
        return "webm"
    if "mov" in names:
        return "mov"
    return next(iter(names), "")
