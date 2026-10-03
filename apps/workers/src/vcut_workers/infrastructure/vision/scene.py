import re
import subprocess
from pathlib import Path

from vcut_workers.application.errors import MediaProcessingError, MediaProcessingLimitError
from vcut_workers.domain.vision import SceneInterval

_PTS_TIME = re.compile(r"pts_time:(?P<timestamp>[0-9]+(?:\.[0-9]+)?)")


class FFmpegSceneDetector:
    """Detect scene boundaries with FFmpeg's deterministic scene score filter."""

    def __init__(
        self,
        binary: str = "ffmpeg",
        *,
        threshold: float = 0.4,
        timeout_seconds: float = 120,
        max_cuts: int = 512,
    ) -> None:
        if not binary or not 0 < threshold <= 1:
            raise ValueError("scene detector settings are invalid")
        if timeout_seconds <= 0 or max_cuts <= 0:
            raise ValueError("scene detector limits must be positive")
        self._binary = binary
        self._threshold = threshold
        self._timeout_seconds = timeout_seconds
        self._max_cuts = max_cuts

    def detect(
        self,
        source: Path,
        *,
        start_seconds: float,
        end_seconds: float,
        duration_seconds: float,
    ) -> tuple[SceneInterval, ...]:
        if not source.is_file():
            raise MediaProcessingError("SOURCE_FILE_NOT_FOUND", "source media was not downloaded")
        if start_seconds < 0 or end_seconds <= start_seconds:
            raise ValueError("scene interval is invalid")
        if end_seconds > duration_seconds:
            raise ValueError("scene interval exceeds video duration")
        interval_duration = end_seconds - start_seconds
        filter_expression = f"select=gt(scene\\,{self._threshold:g}),showinfo"
        try:
            completed = subprocess.run(
                [
                    self._binary,
                    "-hide_banner",
                    "-loglevel",
                    "info",
                    "-ss",
                    f"{start_seconds:.6f}",
                    "-i",
                    str(source),
                    "-t",
                    f"{interval_duration:.6f}",
                    "-vf",
                    filter_expression,
                    "-an",
                    "-f",
                    "null",
                    "-",
                ],
                check=True,
                capture_output=True,
                text=True,
                timeout=self._timeout_seconds,
            )
        except subprocess.TimeoutExpired as error:
            raise MediaProcessingLimitError(
                "VISION_TIME_LIMIT_EXCEEDED", "scene detection exceeded the configured time limit"
            ) from error
        except subprocess.CalledProcessError as error:
            raise MediaProcessingError("SCENE_DETECTION_FAILED", "scene detection failed") from error

        cuts = _parse_cuts(completed.stderr, interval_duration, self._max_cuts)
        boundaries = (0.0, *cuts, interval_duration)
        return tuple(
            SceneInterval(
                start_seconds=start_seconds + left,
                end_seconds=start_seconds + right,
            )
        for left, right in zip(boundaries[:-1], boundaries[1:], strict=True)
            if right > left
        )


def _parse_cuts(stderr: str, duration_seconds: float, max_cuts: int) -> tuple[float, ...]:
    values: list[float] = []
    for match in _PTS_TIME.finditer(stderr):
        timestamp = float(match.group("timestamp"))
        if timestamp <= 0 or timestamp >= duration_seconds:
            continue
        if values and timestamp - values[-1] < 0.05:
            continue
        values.append(timestamp)
        if len(values) >= max_cuts:
            break
    return tuple(values)


__all__ = ["FFmpegSceneDetector"]
