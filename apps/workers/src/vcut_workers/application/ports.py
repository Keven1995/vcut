from pathlib import Path
from typing import Protocol

from vcut_workers.domain.media import (
    AudioMetadata,
    ObjectMetadata,
    VideoAsset,
    VideoMetadata,
    VisionSignal,
)


class VideoProcessor(Protocol):
    def cut(
        self, source: Path, destination: Path, start_seconds: float, end_seconds: float
    ) -> None:
        """Create a media clip for a valid time interval."""

    def probe(self, source: Path) -> VideoMetadata:
        """Read authoritative metadata from a local media file."""


class MediaProcessor(VideoProcessor, Protocol):
    def normalize(
        self,
        source: Path,
        destination: Path,
        *,
        target_fps: float,
        video_codec: str,
        audio_codec: str,
    ) -> VideoMetadata:
        """Normalize a source into the pipeline media contract."""

    def extract_audio(
        self,
        source: Path,
        destination: Path,
        *,
        sample_rate: int,
        channels: int,
    ) -> AudioMetadata:
        """Extract reusable mono audio for a transcription provider."""

    def probe_audio(self, source: Path) -> AudioMetadata:
        """Read authoritative audio metadata."""

    def thumbnail(
        self,
        source: Path,
        destination: Path,
        *,
        timestamp_seconds: float,
        width: int,
        height: int,
    ) -> None:
        """Extract one deterministic thumbnail frame."""

    def sample_frames(
        self,
        source: Path,
        destination_directory: Path,
        *,
        timestamps_seconds: tuple[float, ...],
        width: int,
        height: int,
    ) -> tuple[Path, ...]:
        """Extract deterministic inspection frames."""


class ObjectStorage(Protocol):
    def exists(self, object_key: str) -> bool:
        """Return whether an object exists in the configured storage."""

    def head(self, object_key: str) -> ObjectMetadata | None:
        """Return metadata for an object, or None when it does not exist."""

    def download(self, object_key: str, destination: Path) -> None:
        """Download an object to a local temporary path."""


class WritableObjectStorage(ObjectStorage, Protocol):
    def upload(self, source: Path, object_key: str, content_type: str) -> ObjectMetadata:
        """Upload a local artifact and return its stored metadata."""


class VisionAnalyzer(Protocol):
    def analyze(self, video: VideoAsset) -> tuple[VisionSignal, ...]:
        """Return typed visual signals for a video."""
