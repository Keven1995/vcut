from dataclasses import dataclass


@dataclass(frozen=True)
class VideoAsset:
    object_key: str
    duration_seconds: float

    def __post_init__(self) -> None:
        if not self.object_key:
            raise ValueError("object_key must not be empty")
        if self.duration_seconds < 0:
            raise ValueError("duration_seconds must not be negative")


@dataclass(frozen=True)
class ObjectMetadata:
    object_key: str
    content_length: int
    content_type: str | None = None
    e_tag: str | None = None
    checksum_sha256: str | None = None

    def __post_init__(self) -> None:
        if not self.object_key:
            raise ValueError("object_key must not be empty")
        if self.content_length < 0:
            raise ValueError("content_length must not be negative")


@dataclass(frozen=True)
class VideoMetadata:
    container: str
    duration_seconds: float
    width: int
    height: int
    frame_rate: float | None
    has_audio: bool
    video_codec: str
    audio_codec: str | None
    sample_aspect_ratio: str = "1:1"

    def __post_init__(self) -> None:
        if not self.container or not self.video_codec:
            raise ValueError("container and video_codec must not be empty")
        if self.duration_seconds < 0:
            raise ValueError("duration_seconds must not be negative")
        if self.width <= 0 or self.height <= 0:
            raise ValueError("video dimensions must be positive")
        if self.frame_rate is not None and self.frame_rate < 0:
            raise ValueError("frame_rate must not be negative")
        if not self.sample_aspect_ratio:
            raise ValueError("sample_aspect_ratio must not be empty")


@dataclass(frozen=True)
class AudioMetadata:
    format: str
    duration_seconds: float
    sample_rate: int
    channels: int
    codec: str

    def __post_init__(self) -> None:
        if not self.format or not self.codec:
            raise ValueError("format and codec must not be empty")
        if self.duration_seconds < 0:
            raise ValueError("duration_seconds must not be negative")
        if self.sample_rate <= 0 or self.channels <= 0:
            raise ValueError("sample_rate and channels must be positive")


@dataclass(frozen=True)
class VisionSignal:
    kind: str
    timestamp_seconds: float
    confidence: float

    def __post_init__(self) -> None:
        if not self.kind:
            raise ValueError("kind must not be empty")
        if self.timestamp_seconds < 0:
            raise ValueError("timestamp_seconds must not be negative")
        if not 0 <= self.confidence <= 1:
            raise ValueError("confidence must be between 0 and 1")
