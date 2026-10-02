from math import isfinite
from typing import Annotated
from uuid import UUID

from pydantic import AliasChoices, BaseModel, ConfigDict, Field, model_validator

from vcut_workers.domain.clip_analysis import (
    AnalysisProviderResponse,
    CandidateVariant,
    ClipCandidate,
    DurationPreference,
)
from vcut_workers.domain.transcription import ConfidenceScore, LanguageCode


class AnalysisTranscriptSegment(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    text: str = Field(min_length=1, max_length=10_000)
    start_seconds: float = Field(
        validation_alias=AliasChoices("start_seconds", "start", "startSeconds"),
        serialization_alias="startSeconds",
        ge=0,
    )
    end_seconds: float = Field(
        validation_alias=AliasChoices("end_seconds", "end", "endSeconds"),
        serialization_alias="endSeconds",
        gt=0,
    )
    confidence: ConfidenceScore | None = None

    @model_validator(mode="after")
    def validate_interval(self) -> "AnalysisTranscriptSegment":
        if self.end_seconds <= self.start_seconds:
            raise ValueError("transcript segment end must be greater than start")
        return self


class AnalyzeClipsCommand(BaseModel):
    """Versioned transcript payload consumed by the analysis worker."""

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    video_id: UUID = Field(
        validation_alias=AliasChoices("video_id", "videoId"),
        serialization_alias="videoId",
    )
    pipeline_version: int = Field(
        validation_alias=AliasChoices("pipeline_version", "pipelineVersion"),
        serialization_alias="pipelineVersion",
        ge=1,
    )
    duration_seconds: float = Field(
        validation_alias=AliasChoices("duration_seconds", "durationSeconds"),
        serialization_alias="durationSeconds",
        ge=0,
    )
    language: LanguageCode
    text: str = Field(max_length=1_000_000)
    segments: tuple[AnalysisTranscriptSegment, ...] = Field(min_length=0)
    duration_preference: DurationPreference = Field(
        default=DurationPreference.AUTO,
        validation_alias=AliasChoices("duration_preference", "durationPreference"),
        serialization_alias="durationPreference",
    )
    custom_duration_seconds: Annotated[float | None, Field(gt=0, le=90)] = Field(
        default=None,
        validation_alias=AliasChoices("custom_duration_seconds", "customDurationSeconds"),
        serialization_alias="customDurationSeconds",
    )

    @model_validator(mode="after")
    def validate_segments(self) -> "AnalyzeClipsCommand":
        if not isfinite(self.duration_seconds):
            raise ValueError("analysis duration must be finite")
        if any(segment.end_seconds > self.duration_seconds for segment in self.segments):
            raise ValueError("transcript segment timestamps must be inside the video duration")
        previous_end = 0.0
        for segment in self.segments:
            if segment.start_seconds < previous_end:
                raise ValueError("transcript segments must be ordered")
            previous_end = segment.end_seconds
        if self.duration_preference is DurationPreference.CUSTOM and self.custom_duration_seconds is None:
            raise ValueError("custom duration is required for CUSTOM preference")
        return self


class AnalyzeClipsResult(AnalysisProviderResponse):
    """Validated worker result published to the API."""


class ClipCommand(BaseModel):
    """Future render command boundary; rejects unsafe and invalid LLM output."""

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    video_id: UUID = Field(
        validation_alias=AliasChoices("video_id", "videoId"),
        serialization_alias="videoId",
    )
    start_seconds: float = Field(
        validation_alias=AliasChoices("start_seconds", "start", "startSeconds"),
        serialization_alias="startSeconds",
        ge=0,
    )
    end_seconds: float = Field(
        validation_alias=AliasChoices("end_seconds", "end", "endSeconds"),
        serialization_alias="endSeconds",
        gt=0,
    )
    aspect_ratio: str = Field(
        validation_alias=AliasChoices("aspect_ratio", "aspectRatio"),
        serialization_alias="aspectRatio",
        pattern=r"^(9:16|16:9)$",
    )
    candidate_variant: CandidateVariant = Field(
        validation_alias=AliasChoices("candidate_variant", "candidateVariant"),
        serialization_alias="candidateVariant",
    )

    @model_validator(mode="after")
    def validate_interval(self) -> "ClipCommand":
        if self.end_seconds <= self.start_seconds:
            raise ValueError("clip command end must be greater than start")
        if self.end_seconds - self.start_seconds > 90:
            raise ValueError("clip command duration must not exceed 90 seconds")
        return self


__all__ = [
    "AnalysisProviderResponse",
    "AnalyzeClipsCommand",
    "AnalyzeClipsResult",
    "AnalysisTranscriptSegment",
    "CandidateVariant",
    "ClipCandidate",
    "ClipCommand",
    "DurationPreference",
]
