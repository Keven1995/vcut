from enum import StrEnum
from math import isfinite
from typing import Annotated
from uuid import UUID

from pydantic import AliasChoices, BaseModel, ConfigDict, Field, model_validator

from vcut_workers.domain.transcription import ConfidenceScore, LanguageCode


class DurationPreference(StrEnum):
    SHORT = "SHORT"
    MEDIUM = "MEDIUM"
    LONG = "LONG"
    AUTO = "AUTO"
    CUSTOM = "CUSTOM"


class CandidateVariant(StrEnum):
    SHORT = "SHORT"
    COMPLETE = "COMPLETE"
    CONTEXTUAL = "CONTEXTUAL"


def _range_is_valid(start: float, end: float, label: str) -> None:
    if not isfinite(start) or not isfinite(end) or start < 0 or end <= start:
        raise ValueError(f"{label} interval is invalid")


class SemanticSegment(BaseModel):
    """A deterministic semantic unit derived from transcript timestamps."""

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
    source_segment_indexes: tuple[int, ...] = Field(
        validation_alias=AliasChoices("source_segment_indexes", "sourceSegmentIndexes"),
        serialization_alias="sourceSegmentIndexes",
    )

    @model_validator(mode="after")
    def validate_interval(self) -> "SemanticSegment":
        _range_is_valid(self.start_seconds, self.end_seconds, "semantic segment")
        if not self.source_segment_indexes:
            raise ValueError("semantic segment must have a source segment")
        return self


class CandidateScores(BaseModel):
    """Internal dimensions used for deterministic ranking."""

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    hook: ConfidenceScore
    context: ConfidenceScore
    development: ConfidenceScore
    payoff: ConfidenceScore
    independence: ConfidenceScore
    engagement: ConfidenceScore


class AnalysisUsageMetrics(BaseModel):
    """Non-sensitive consumption measurements emitted with one analysis result."""

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    llm_tokens: int = Field(default=0, alias="llmTokens", ge=0)
    multimodal_minutes: float = Field(default=0, alias="multimodalMinutes", ge=0)
    cpu_seconds: float = Field(default=0, alias="cpuSeconds", ge=0)
    gpu_seconds: float = Field(default=0, alias="gpuSeconds", ge=0)
    bandwidth_bytes: int = Field(default=0, alias="bandwidthBytes", ge=0)


class ClipCandidate(BaseModel):
    """A validated candidate interval; internal scores never need to be exposed."""

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    id: UUID
    group_id: UUID = Field(
        validation_alias=AliasChoices("group_id", "groupId"),
        serialization_alias="groupId",
    )
    video_id: UUID = Field(
        validation_alias=AliasChoices("video_id", "videoId"),
        serialization_alias="videoId",
    )
    pipeline_version: int = Field(
        validation_alias=AliasChoices("pipeline_version", "pipelineVersion"),
        serialization_alias="pipelineVersion",
        ge=1,
    )
    variant: CandidateVariant
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
    title: str = Field(min_length=1, max_length=160)
    description: str = Field(min_length=1, max_length=1_000)
    justification: str = Field(min_length=1, max_length=1_000)
    scores: CandidateScores
    internal_score: ConfidenceScore = Field(
        validation_alias=AliasChoices("internal_score", "internalScore"),
        serialization_alias="internalScore",
    )

    @model_validator(mode="after")
    def validate_interval(self) -> "ClipCandidate":
        _range_is_valid(self.start_seconds, self.end_seconds, "clip candidate")
        if self.end_seconds - self.start_seconds > 90:
            raise ValueError("clip candidate duration must not exceed 90 seconds")
        return self


class AnalysisProviderResponse(BaseModel):
    """Untrusted provider output after boundary validation."""

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    provider: str = Field(min_length=1, max_length=128)
    duration_seconds: float = Field(
        validation_alias=AliasChoices("duration_seconds", "durationSeconds"),
        serialization_alias="durationSeconds",
        ge=0,
    )
    candidates: tuple[ClipCandidate, ...] = Field(min_length=0, max_length=20)
    has_reliable_candidate: bool = Field(
        validation_alias=AliasChoices("has_reliable_candidate", "hasReliableCandidate"),
        serialization_alias="hasReliableCandidate",
    )
    usage_metrics: AnalysisUsageMetrics | None = Field(
        default=None,
        validation_alias=AliasChoices("usage_metrics", "usageMetrics"),
        serialization_alias="usageMetrics",
    )

    @model_validator(mode="after")
    def validate_candidates(self) -> "AnalysisProviderResponse":
        if not isfinite(self.duration_seconds):
            raise ValueError("analysis duration must be finite")
        if any(candidate.end_seconds > self.duration_seconds for candidate in self.candidates):
            raise ValueError("candidate timestamps must be inside the video duration")
        if self.has_reliable_candidate != bool(self.candidates):
            raise ValueError("reliability flag must match candidate presence")
        return self


AnalysisLanguage = LanguageCode
AnalysisConfidence = ConfidenceScore
CustomDuration = Annotated[float, Field(gt=0, le=90)]
