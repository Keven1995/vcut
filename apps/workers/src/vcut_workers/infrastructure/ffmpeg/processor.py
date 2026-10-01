import json
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import cast

from vcut_workers.application.errors import MediaProcessingError, MediaProcessingLimitError
from vcut_workers.domain.media import AudioMetadata, VideoMetadata


@dataclass(frozen=True)
class FFmpegExecutionLimits:
    timeout_seconds: float = 900
    max_temp_bytes: int = 4_294_967_296
    max_memory_bytes: int = 1_073_741_824

    def __post_init__(self) -> None:
        if self.timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be positive")
        if self.max_temp_bytes <= 0 or self.max_memory_bytes <= 0:
            raise ValueError("media limits must be positive")


class FFmpegVideoProcessor:
    def __init__(
        self,
        binary: str = "ffmpeg",
        probe_binary: str = "ffprobe",
        execution_limits: FFmpegExecutionLimits | None = None,
    ) -> None:
        if not binary:
            raise ValueError("binary must not be empty")
        if not probe_binary:
            raise ValueError("probe_binary must not be empty")
        self._binary = binary
        self._probe_binary = probe_binary
        self._limits = execution_limits or FFmpegExecutionLimits()

    def normalize(
        self,
        source: Path,
        destination: Path,
        *,
        target_fps: float,
        video_codec: str = "libx264",
        audio_codec: str = "aac",
    ) -> VideoMetadata:
        if target_fps <= 0:
            raise ValueError("target_fps must be positive")
        self._run(
            [
                self._binary,
                "-autorotate",
                "-max_alloc",
                str(self._limits.max_memory_bytes),
                "-y",
                "-i",
                str(source),
                "-map",
                "0:v:0",
                "-map",
                "0:a:0?",
                "-r",
                str(target_fps),
                "-fps_mode",
                "cfr",
                "-c:v",
                video_codec,
                "-pix_fmt",
                "yuv420p",
                "-c:a",
                audio_codec,
                "-movflags",
                "+faststart",
                "-map_metadata",
                "0",
                "-metadata:s:v:0",
                "rotate=0",
                str(destination),
            ],
            output_paths=(destination,),
        )
        return self.probe(destination)

    def extract_audio(
        self,
        source: Path,
        destination: Path,
        *,
        sample_rate: int = 16_000,
        channels: int = 1,
    ) -> AudioMetadata:
        if sample_rate <= 0 or channels <= 0:
            raise ValueError("sample_rate and channels must be positive")
        self._run(
            [
                self._binary,
                "-max_alloc",
                str(self._limits.max_memory_bytes),
                "-y",
                "-i",
                str(source),
                "-map",
                "0:a:0",
                "-vn",
                "-ac",
                str(channels),
                "-ar",
                str(sample_rate),
                "-acodec",
                "pcm_s16le",
                str(destination),
            ],
            output_paths=(destination,),
        )
        return self.probe_audio(destination)

    def cut(
        self, source: Path, destination: Path, start_seconds: float, end_seconds: float
    ) -> None:
        if start_seconds < 0 or end_seconds <= start_seconds:
            raise ValueError("end_seconds must be greater than start_seconds")
        self._run(
            [
                self._binary,
                "-max_alloc",
                str(self._limits.max_memory_bytes),
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
            ],
            output_paths=(destination,),
        )

    def thumbnail(
        self,
        source: Path,
        destination: Path,
        *,
        timestamp_seconds: float = 0,
        width: int = 640,
        height: int = 360,
    ) -> None:
        if timestamp_seconds < 0 or width <= 0 or height <= 0:
            raise ValueError("thumbnail arguments are invalid")
        self._run(
            [
                self._binary,
                "-max_alloc",
                str(self._limits.max_memory_bytes),
                "-y",
                "-ss",
                str(timestamp_seconds),
                "-i",
                str(source),
                "-frames:v",
                "1",
                "-vf",
                f"scale={width}:{height}:force_original_aspect_ratio=decrease",
                "-q:v",
                "2",
                str(destination),
            ],
            output_paths=(destination,),
        )

    def sample_frames(
        self,
        source: Path,
        destination_directory: Path,
        *,
        timestamps_seconds: tuple[float, ...],
        width: int = 640,
        height: int = 360,
    ) -> tuple[Path, ...]:
        destination_directory.mkdir(parents=True, exist_ok=True)
        paths: list[Path] = []
        for index, timestamp_seconds in enumerate(timestamps_seconds):
            destination = destination_directory / f"frame-{index:04d}.jpg"
            self.thumbnail(
                source,
                destination,
                timestamp_seconds=timestamp_seconds,
                width=width,
                height=height,
            )
            paths.append(destination)
        return tuple(paths)

    def probe(self, source: Path) -> VideoMetadata:
        payload = self._probe(
            source,
            "format=format_name,duration:stream=codec_type,codec_name,width,height,avg_frame_rate",
        )
        streams = _objects(payload.get("streams"))
        video_stream = next(
            (stream for stream in streams if stream.get("codec_type") == "video"), None
        )
        if video_stream is None:
            raise ValueError("media does not contain a video stream")
        format_payload = _object(payload.get("format"))
        format_name = _normalize_container(str(format_payload.get("format_name", "")))
        return VideoMetadata(
            container=format_name,
            duration_seconds=_float_value(format_payload.get("duration", 0)),
            width=_int_value(video_stream.get("width", 0)),
            height=_int_value(video_stream.get("height", 0)),
            frame_rate=_parse_frame_rate(video_stream.get("avg_frame_rate")),
            has_audio=any(stream.get("codec_type") == "audio" for stream in streams),
            video_codec=str(video_stream.get("codec_name", "")),
            audio_codec=next(
                (
                    str(stream.get("codec_name", ""))
                    for stream in streams
                    if stream.get("codec_type") == "audio"
                ),
                None,
            ),
        )

    def probe_audio(self, source: Path) -> AudioMetadata:
        payload = self._probe(
            source,
            "format=format_name,duration:stream=codec_type,codec_name,sample_rate,channels",
        )
        streams = _objects(payload.get("streams"))
        audio_stream = next(
            (stream for stream in streams if stream.get("codec_type") == "audio"), None
        )
        if audio_stream is None:
            raise ValueError("media does not contain an audio stream")
        format_payload = _object(payload.get("format"))
        return AudioMetadata(
            format=_normalize_container(str(format_payload.get("format_name", ""))),
            duration_seconds=_float_value(format_payload.get("duration", 0)),
            sample_rate=_int_value(audio_stream.get("sample_rate", 0)),
            channels=_int_value(audio_stream.get("channels", 0)),
            codec=str(audio_stream.get("codec_name", "")),
        )

    def _probe(self, source: Path, entries: str) -> dict[str, object]:
        try:
            completed = subprocess.run(
                [
                    self._probe_binary,
                    "-v",
                    "error",
                    "-show_entries",
                    entries,
                    "-of",
                    "json",
                    str(source),
                ],
                check=True,
                capture_output=True,
                text=True,
                timeout=self._limits.timeout_seconds,
            )
        except subprocess.TimeoutExpired as error:
            raise MediaProcessingLimitError(
                "PROCESSING_TIME_LIMIT_EXCEEDED",
                "media probing exceeded the configured time limit",
            ) from error
        except subprocess.CalledProcessError as error:
            raise MediaProcessingError("FFPROBE_FAILED", "media probing failed") from error
        payload = json.loads(completed.stdout)
        if not isinstance(payload, dict):
            raise ValueError("ffprobe returned an invalid payload")
        return payload

    def _run(self, command: list[str], *, output_paths: tuple[Path, ...] = ()) -> None:
        for path in output_paths:
            path.parent.mkdir(parents=True, exist_ok=True)
        succeeded = False
        try:
            subprocess.run(
                command,
                check=True,
                capture_output=True,
                text=True,
                timeout=self._limits.timeout_seconds,
            )
            output_size = sum(path.stat().st_size for path in output_paths if path.exists())
            if output_size > self._limits.max_temp_bytes:
                raise MediaProcessingLimitError(
                    "TEMPORARY_STORAGE_LIMIT_EXCEEDED",
                    "media processing output exceeded the temporary storage limit",
                )
            succeeded = True
        except subprocess.TimeoutExpired as error:
            raise MediaProcessingLimitError(
                "PROCESSING_TIME_LIMIT_EXCEEDED",
                "media processing exceeded the configured time limit",
            ) from error
        except subprocess.CalledProcessError as error:
            stderr = error.stderr if isinstance(error.stderr, str) else ""
            if "memory" in stderr.lower() or "max_alloc" in stderr.lower():
                raise MediaProcessingLimitError(
                    "MEMORY_LIMIT_EXCEEDED",
                    "media processing exceeded the configured memory limit",
                ) from error
            raise MediaProcessingError("FFMPEG_FAILED", "media processing failed") from error
        except OSError:
            raise
        finally:
            if not succeeded:
                for path in output_paths:
                    path.unlink(missing_ok=True)


def _parse_frame_rate(value: object) -> float | None:
    if not isinstance(value, str) or not value or value in {"0/0", "N/A"}:
        return None
    numerator, denominator = value.split("/", 1)
    if float(denominator) == 0:
        return None
    return float(numerator) / float(denominator)


def _object(value: object) -> dict[str, object]:
    return cast(dict[str, object], value) if isinstance(value, dict) else {}


def _objects(value: object) -> tuple[dict[str, object], ...]:
    if not isinstance(value, list):
        return ()
    return tuple(cast(dict[str, object], item) for item in value if isinstance(item, dict))


def _float_value(value: object) -> float:
    if isinstance(value, int | float | str):
        return float(value)
    raise ValueError("ffprobe returned a non-numeric value")


def _int_value(value: object) -> int:
    if isinstance(value, int | float | str):
        return int(value)
    raise ValueError("ffprobe returned a non-numeric value")


def _normalize_container(value: str) -> str:
    names = {part.strip().lower() for part in value.split(",")}
    if "mp4" in names:
        return "mp4"
    if "webm" in names:
        return "webm"
    if "mov" in names:
        return "mov"
    return next(iter(names), "")
