from collections.abc import Sequence
from math import isfinite
from typing import Annotated, Protocol

from pydantic import BaseModel, ConfigDict, Field, model_validator


class SceneIntervalPayload(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    start_seconds: Annotated[float, Field(alias="startSeconds", ge=0)]
    end_seconds: Annotated[float, Field(alias="endSeconds", gt=0)]
    confidence: float = Field(default=1, ge=0, le=1)

    @model_validator(mode="after")
    def validate_interval(self) -> "SceneIntervalPayload":
        if not isfinite(self.start_seconds) or not isfinite(self.end_seconds):
            raise ValueError("scene timestamps must be finite")
        if self.end_seconds <= self.start_seconds:
            raise ValueError("scene interval must be positive")
        return self


class BoundingBoxPayload(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    x: float = Field(ge=0, le=1)
    y: float = Field(ge=0, le=1)
    width: float = Field(gt=0, le=1)
    height: float = Field(gt=0, le=1)

    @model_validator(mode="after")
    def validate_bounds(self) -> "BoundingBoxPayload":
        if self.x + self.width > 1 or self.y + self.height > 1:
            raise ValueError("bounding box must fit inside the frame")
        return self


class FaceDetectionPayload(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    timestamp_seconds: Annotated[float, Field(alias="timestampSeconds", ge=0)]
    bounding_box: Annotated[BoundingBoxPayload, Field(alias="boundingBox")]
    confidence: float = Field(ge=0, le=1)
    track_id: Annotated[int | None, Field(alias="trackId", ge=0)] = None
    speaking_confidence: Annotated[float, Field(alias="speakingConfidence", ge=0, le=1)] = 0


class VisualRegionPayload(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    timestamp_seconds: Annotated[float, Field(alias="timestampSeconds", ge=0)]
    center_x: Annotated[float, Field(alias="centerX", ge=0, le=1)]
    center_y: Annotated[float, Field(alias="centerY", ge=0, le=1)]
    confidence: float = Field(ge=0, le=1)
    kind: str = Field(min_length=1, max_length=32)


class SpeakingIntervalPayload(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    start_seconds: Annotated[float, Field(alias="startSeconds", ge=0)]
    end_seconds: Annotated[float, Field(alias="endSeconds", gt=0)]
    confidence: float = Field(ge=0, le=1)

    @model_validator(mode="after")
    def validate_interval(self) -> "SpeakingIntervalPayload":
        if not isfinite(self.start_seconds) or not isfinite(self.end_seconds):
            raise ValueError("speaking timestamps must be finite")
        if self.end_seconds <= self.start_seconds:
            raise ValueError("speaking interval must be positive")
        return self


class _IntervalLike(Protocol):
    start_seconds: float
    end_seconds: float


class _TimestampLike(Protocol):
    timestamp_seconds: float


class VisualAnalysisPayload(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    duration_seconds: Annotated[float, Field(alias="durationSeconds", ge=0)]
    scenes: tuple[SceneIntervalPayload, ...] = ()
    faces: tuple[FaceDetectionPayload, ...] = ()
    regions: tuple[VisualRegionPayload, ...] = ()
    speaking_intervals: Annotated[
        tuple[SpeakingIntervalPayload, ...], Field(alias="speakingIntervals")
    ] = ()

    @model_validator(mode="after")
    def validate_order(self) -> "VisualAnalysisPayload":
        _validate_monotonic_intervals(self.scenes, "scenes")
        _validate_monotonic_timestamps(self.faces, "faces")
        _validate_monotonic_timestamps(self.regions, "regions")
        _validate_monotonic_intervals(self.speaking_intervals, "speaking intervals")
        for scene in self.scenes:
            if scene.end_seconds > self.duration_seconds:
                raise ValueError("scene is outside the analyzed duration")
        for interval in self.speaking_intervals:
            if interval.end_seconds > self.duration_seconds:
                raise ValueError("speaking interval is outside the analyzed duration")
        return self


def _validate_monotonic_intervals(values: Sequence[_IntervalLike], label: str) -> None:
    previous_end = 0.0
    for value in values:
        start = value.start_seconds
        end = value.end_seconds
        if start < previous_end:
            raise ValueError(f"{label} must be monotonic and non-overlapping")
        previous_end = end


def _validate_monotonic_timestamps(values: Sequence[_TimestampLike], label: str) -> None:
    previous_timestamp = 0.0
    for value in values:
        timestamp = value.timestamp_seconds
        if timestamp < previous_timestamp:
            raise ValueError(f"{label} timestamps must be monotonic")
        previous_timestamp = timestamp


__all__ = [
    "BoundingBoxPayload",
    "FaceDetectionPayload",
    "SceneIntervalPayload",
    "SpeakingIntervalPayload",
    "VisualAnalysisPayload",
    "VisualRegionPayload",
]
