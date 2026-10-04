from enum import StrEnum
from math import isfinite

from pydantic import BaseModel, ConfigDict, Field, model_validator


class MultimodalTopic(StrEnum):
    REACTION = "REACTION"
    GAMEPLAY = "GAMEPLAY"
    VISUAL_CONTEXT = "VISUAL_CONTEXT"


class EvidenceModality(StrEnum):
    TRANSCRIPT = "TRANSCRIPT"
    VISUAL = "VISUAL"


class MultimodalFinding(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    start_seconds: float = Field(alias="startSeconds", ge=0)
    end_seconds: float = Field(alias="endSeconds", gt=0)
    topic: MultimodalTopic
    evidence: tuple[EvidenceModality, ...] = Field(min_length=1)
    confidence: float = Field(ge=0, le=1)
    summary: str = Field(min_length=1, max_length=240)

    @model_validator(mode="after")
    def validate_finite_interval(self) -> "MultimodalFinding":
        if not isfinite(self.start_seconds) or not isfinite(self.end_seconds):
            raise ValueError("multimodal timestamps must be finite")
        if self.end_seconds <= self.start_seconds:
            raise ValueError("multimodal interval must be positive")
        return self


class MultimodalAnalysis(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    provider: str = Field(min_length=1, max_length=64)
    duration_seconds: float = Field(alias="durationSeconds", ge=0)
    findings: tuple[MultimodalFinding, ...] = ()
    transcription_fallback: bool = Field(default=False, alias="transcriptionFallback")

    @model_validator(mode="after")
    def validate_findings(self) -> "MultimodalAnalysis":
        if not isfinite(self.duration_seconds):
            raise ValueError("multimodal duration must be finite")
        previous_start = 0.0
        for finding in self.findings:
            if finding.end_seconds > self.duration_seconds:
                raise ValueError("multimodal finding is outside the video duration")
            if finding.start_seconds < previous_start:
                raise ValueError("multimodal findings must be ordered")
            previous_start = finding.start_seconds
        return self


__all__ = [
    "EvidenceModality",
    "MultimodalAnalysis",
    "MultimodalFinding",
    "MultimodalTopic",
]
