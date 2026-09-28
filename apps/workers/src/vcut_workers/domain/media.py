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
