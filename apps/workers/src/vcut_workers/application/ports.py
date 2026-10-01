from pathlib import Path
from typing import Protocol

from vcut_workers.domain.media import ObjectMetadata, VideoAsset, VideoMetadata, VisionSignal


class VideoProcessor(Protocol):
    def extract_audio(self, source: Path, destination: Path) -> None:
        """Extract an audio track from a local media file."""

    def cut(
        self, source: Path, destination: Path, start_seconds: float, end_seconds: float
    ) -> None:
        """Create a media clip for a valid time interval."""

    def probe(self, source: Path) -> VideoMetadata:
        """Read authoritative metadata from a local media file."""


class ObjectStorage(Protocol):
    def exists(self, object_key: str) -> bool:
        """Return whether an object exists in the configured storage."""

    def head(self, object_key: str) -> ObjectMetadata | None:
        """Return metadata for an object, or None when it does not exist."""

    def download(self, object_key: str, destination: Path) -> None:
        """Download an object to a local temporary path."""


class VisionAnalyzer(Protocol):
    def analyze(self, video: VideoAsset) -> tuple[VisionSignal, ...]:
        """Return typed visual signals for a video."""
