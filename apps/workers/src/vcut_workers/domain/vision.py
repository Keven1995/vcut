from dataclasses import dataclass
from enum import StrEnum
from math import isfinite


class RegionKind(StrEnum):
    SPEAKER = "speaker"
    FACE = "face"
    MOTION = "motion"
    EVENT = "event"
    INTEREST = "interest"


@dataclass(frozen=True)
class SceneInterval:
    start_seconds: float
    end_seconds: float
    confidence: float = 1.0

    def __post_init__(self) -> None:
        if not isfinite(self.start_seconds) or not isfinite(self.end_seconds):
            raise ValueError("scene timestamps must be finite")
        if self.start_seconds < 0 or self.end_seconds <= self.start_seconds:
            raise ValueError("scene interval must be positive and non-negative")
        if not 0 <= self.confidence <= 1:
            raise ValueError("scene confidence must be between 0 and 1")


@dataclass(frozen=True)
class BoundingBox:
    x: float
    y: float
    width: float
    height: float

    def __post_init__(self) -> None:
        values = (self.x, self.y, self.width, self.height)
        if not all(isfinite(value) for value in values):
            raise ValueError("bounding box values must be finite")
        if self.x < 0 or self.y < 0 or self.width <= 0 or self.height <= 0:
            raise ValueError("bounding box must have positive dimensions")
        if self.x + self.width > 1 or self.y + self.height > 1:
            raise ValueError("bounding box must fit inside the frame")

    @property
    def center_x(self) -> float:
        return self.x + self.width / 2

    @property
    def center_y(self) -> float:
        return self.y + self.height / 2


@dataclass(frozen=True)
class FaceDetection:
    timestamp_seconds: float
    bounding_box: BoundingBox
    confidence: float
    track_id: int | None = None
    speaking_confidence: float = 0

    def __post_init__(self) -> None:
        if not isfinite(self.timestamp_seconds) or self.timestamp_seconds < 0:
            raise ValueError("face timestamp must be finite and non-negative")
        if not 0 <= self.confidence <= 1:
            raise ValueError("face confidence must be between 0 and 1")
        if not 0 <= self.speaking_confidence <= 1:
            raise ValueError("speaking confidence must be between 0 and 1")
        if self.track_id is not None and self.track_id < 0:
            raise ValueError("track_id must be non-negative")


@dataclass(frozen=True)
class VisualRegion:
    timestamp_seconds: float
    center_x: float
    center_y: float
    confidence: float
    kind: RegionKind = RegionKind.INTEREST

    def __post_init__(self) -> None:
        if not isfinite(self.timestamp_seconds) or self.timestamp_seconds < 0:
            raise ValueError("region timestamp must be finite and non-negative")
        if not all(isfinite(value) for value in (self.center_x, self.center_y)):
            raise ValueError("region coordinates must be finite")
        if not 0 <= self.center_x <= 1 or not 0 <= self.center_y <= 1:
            raise ValueError("region coordinates must be between 0 and 1")
        if not 0 <= self.confidence <= 1:
            raise ValueError("region confidence must be between 0 and 1")


@dataclass(frozen=True)
class SpeakingInterval:
    start_seconds: float
    end_seconds: float
    confidence: float

    def __post_init__(self) -> None:
        if not isfinite(self.start_seconds) or not isfinite(self.end_seconds):
            raise ValueError("speaking timestamps must be finite")
        if self.start_seconds < 0 or self.end_seconds <= self.start_seconds:
            raise ValueError("speaking interval must be positive and non-negative")
        if not 0 <= self.confidence <= 1:
            raise ValueError("speaking confidence must be between 0 and 1")


@dataclass(frozen=True)
class VisualAnalysis:
    duration_seconds: float
    scenes: tuple[SceneInterval, ...] = ()
    faces: tuple[FaceDetection, ...] = ()
    regions: tuple[VisualRegion, ...] = ()
    speaking_intervals: tuple[SpeakingInterval, ...] = ()

    def __post_init__(self) -> None:
        if not isfinite(self.duration_seconds) or self.duration_seconds < 0:
            raise ValueError("analysis duration must be finite and non-negative")
        _validate_scene_order(self.scenes)
        _validate_face_order(self.faces)
        _validate_region_order(self.regions)
        _validate_speaking_order(self.speaking_intervals)
        for scene in self.scenes:
            if scene.end_seconds > self.duration_seconds:
                raise ValueError("scene interval is outside the analyzed video")
        for interval in self.speaking_intervals:
            if interval.end_seconds > self.duration_seconds:
                raise ValueError("speaking interval is outside the analyzed video")

    @classmethod
    def empty(cls, duration_seconds: float) -> "VisualAnalysis":
        scenes = (
            (SceneInterval(0, duration_seconds),)
            if duration_seconds > 0
            else ()
        )
        return cls(duration_seconds=duration_seconds, scenes=scenes)


def _validate_scene_order(intervals: tuple[SceneInterval, ...]) -> None:
    previous_end = 0.0
    for interval in intervals:
        if interval.start_seconds < previous_end:
            raise ValueError("scene intervals must be ordered and non-overlapping")
        previous_end = interval.end_seconds


def _validate_face_order(values: tuple[FaceDetection, ...]) -> None:
    previous_timestamp = 0.0
    for value in values:
        if value.timestamp_seconds < previous_timestamp:
            raise ValueError("face detection timestamps must be monotonic")
        previous_timestamp = value.timestamp_seconds


def _validate_region_order(values: tuple[VisualRegion, ...]) -> None:
    previous_timestamp = 0.0
    for value in values:
        if value.timestamp_seconds < previous_timestamp:
            raise ValueError("visual region timestamps must be monotonic")
        previous_timestamp = value.timestamp_seconds


def _validate_speaking_order(values: tuple[SpeakingInterval, ...]) -> None:
    previous_end = 0.0
    for interval in values:
        start = interval.start_seconds
        end = interval.end_seconds
        if start < previous_end:
            raise ValueError("speaking intervals must be ordered and non-overlapping")
        previous_end = end


__all__ = [
    "BoundingBox",
    "FaceDetection",
    "RegionKind",
    "SceneInterval",
    "SpeakingInterval",
    "VisualAnalysis",
    "VisualRegion",
]
