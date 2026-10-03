from pathlib import Path
from typing import Protocol
from uuid import UUID

from vcut_workers.contracts.clip_analysis import AnalyzeClipsCommand
from vcut_workers.domain.clip_analysis import AnalysisProviderResponse, SemanticSegment
from vcut_workers.domain.clip_generation import ClipComposition
from vcut_workers.domain.media import (
    AudioMetadata,
    ObjectMetadata,
    VideoAsset,
    VideoMetadata,
    VisionSignal,
)
from vcut_workers.domain.transcription import TranscriptionResult
from vcut_workers.domain.vision import SceneInterval


class VideoProcessor(Protocol):
    def cut(
        self, source: Path, destination: Path, start_seconds: float, end_seconds: float
    ) -> None:
        """Create a media clip for a valid time interval."""

    def probe(self, source: Path) -> VideoMetadata:
        """Read authoritative metadata from a local media file."""


class MediaProcessor(VideoProcessor, Protocol):
    def compose(
        self,
        source: Path,
        destination: Path,
        composition: ClipComposition,
    ) -> VideoMetadata:
        """Compose a validated interval, aspect ratio and caption track."""

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

    def delete(self, object_key: str) -> None:
        """Delete an object when a processing attempt cannot produce a valid result."""


class VisionAnalyzer(Protocol):
    def analyze(self, video: VideoAsset) -> tuple[VisionSignal, ...]:
        """Return typed visual signals for a video."""


class SmartCropAnalyzer(Protocol):
    def crop_for(
        self,
        source: Path,
        *,
        video: VideoAsset,
        video_id: UUID,
        pipeline_version: int,
        start_seconds: float,
        end_seconds: float,
        fallback_x: float,
        fallback_y: float,
        fallback_zoom: float,
    ) -> tuple[float, float, float]:
        """Return a bounded crop center and zoom, or the provided fallback."""


class SceneIntervalStore(Protocol):
    def save(
        self,
        video_id: UUID,
        pipeline_version: int,
        intervals: tuple[SceneInterval, ...],
    ) -> None:
        """Persist scene intervals for one immutable pipeline version."""


class TranscriptionProvider(Protocol):
    def transcribe(self, audio_path: Path, *, language: str | None = None) -> object:
        """Return an untrusted provider payload for boundary validation."""


class TranscriptionResultStore(Protocol):
    def get(self, video_id: UUID, pipeline_version: int) -> TranscriptionResult | None:
        """Return the immutable result for one video and pipeline version."""

    def save(self, result: TranscriptionResult) -> TranscriptionResult:
        """Persist a result without replacing another result for the same version."""


class ContentAnalyzer(Protocol):
    def analyze(
        self,
        command: AnalyzeClipsCommand,
        segments: tuple[SemanticSegment, ...],
    ) -> AnalysisProviderResponse:
        """Return untrusted semantic analysis output for boundary validation."""
