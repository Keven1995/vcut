from pathlib import Path

from vcut_workers.domain.media import VideoAsset, VisionSignal
from vcut_workers.domain.vision import FaceDetection, SceneInterval


class DeterministicVisionAnalyzer:
    def analyze(self, video: VideoAsset) -> tuple[VisionSignal, ...]:
        """Return no signals until a vision provider is configured."""
        return ()


class DeterministicSceneDetector:
    """Safe scene fallback that treats the analyzed interval as one scene."""

    def detect(
        self,
        source: Path,
        *,
        start_seconds: float,
        end_seconds: float,
        duration_seconds: float,
    ) -> tuple[SceneInterval, ...]:
        del source, duration_seconds
        return (SceneInterval(start_seconds, end_seconds),)


class DeterministicFaceDetector:
    """Safe face fallback that never requires a vision runtime."""

    def detect(
        self, source: Path, *, timestamps_seconds: tuple[float, ...]
    ) -> tuple[FaceDetection, ...]:
        del source, timestamps_seconds
        return ()


__all__ = [
    "DeterministicFaceDetector",
    "DeterministicSceneDetector",
    "DeterministicVisionAnalyzer",
]
