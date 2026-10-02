from datetime import UTC, datetime
from math import isfinite
from typing import Annotated, Literal
from uuid import UUID

from pydantic import (
    AliasChoices,
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    model_validator,
)

LanguageCode = Annotated[
    str,
    StringConstraints(
        strip_whitespace=True,
        min_length=2,
        max_length=16,
        pattern=r"^[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8})?$",
    ),
]
ConfidenceScore = Annotated[float, Field(ge=0, le=1)]


def _validate_range(start: float, end: float, label: str) -> None:
    if not isfinite(start) or not isfinite(end):
        raise ValueError(f"{label} timestamps must be finite")
    if end <= start:
        raise ValueError(f"{label} end must be greater than start")


class TranscriptionWord(BaseModel):
    """A word with an optional provider confidence and a valid time range."""

    model_config = ConfigDict(
        extra="forbid",
        frozen=True,
        populate_by_name=True,
    )

    text: str = Field(
        min_length=1,
        max_length=1_000,
        validation_alias=AliasChoices("text", "word"),
    )
    start_seconds: float = Field(alias="start", ge=0)
    end_seconds: float = Field(alias="end", gt=0)
    confidence: ConfidenceScore | None = None

    @model_validator(mode="after")
    def validate_range(self) -> "TranscriptionWord":
        _validate_range(self.start_seconds, self.end_seconds, "word")
        return self


class TranscriptionSegment(BaseModel):
    """A temporal transcript segment containing zero or more words."""

    model_config = ConfigDict(
        extra="forbid",
        frozen=True,
        populate_by_name=True,
    )

    text: str = Field(min_length=1, max_length=10_000)
    start_seconds: float = Field(alias="start", ge=0)
    end_seconds: float = Field(alias="end", gt=0)
    confidence: ConfidenceScore | None = None
    words: tuple[TranscriptionWord, ...] = Field(default_factory=tuple)

    @model_validator(mode="after")
    def validate_range_and_words(self) -> "TranscriptionSegment":
        _validate_range(self.start_seconds, self.end_seconds, "segment")
        previous_end = self.start_seconds
        for word in self.words:
            if word.start_seconds < self.start_seconds or word.end_seconds > self.end_seconds:
                raise ValueError("word timestamps must be contained by their segment")
            if word.start_seconds < previous_end:
                raise ValueError("word timestamps must be ordered")
            previous_end = word.end_seconds
        return self


class TranscriptionProviderResponse(BaseModel):
    """Validated response shape accepted from every transcription provider."""

    model_config = ConfigDict(
        extra="forbid",
        frozen=True,
        populate_by_name=True,
    )

    language: LanguageCode = Field(
        validation_alias=AliasChoices("language", "languageCode"),
    )
    text: str = Field(max_length=1_000_000)
    duration_seconds: float = Field(
        validation_alias=AliasChoices("durationSeconds", "duration"),
        serialization_alias="durationSeconds",
        ge=0,
    )
    confidence: ConfidenceScore | None = None
    segments: tuple[TranscriptionSegment, ...] = Field(min_length=0)

    @model_validator(mode="after")
    def validate_segments(self) -> "TranscriptionProviderResponse":
        if not isfinite(self.duration_seconds):
            raise ValueError("transcription duration must be finite")
        previous_end = 0.0
        for segment in self.segments:
            if segment.end_seconds > self.duration_seconds:
                raise ValueError("segment timestamps must be inside the transcription duration")
            if segment.start_seconds < previous_end:
                raise ValueError("segment timestamps must be ordered")
            previous_end = segment.end_seconds
        return self


class TranscriptionResult(BaseModel):
    """Versioned, API-facing transcription associated with one video."""

    model_config = ConfigDict(
        extra="forbid",
        frozen=True,
        populate_by_name=True,
    )

    schema_version: Literal[1] = Field(default=1, alias="schemaVersion")
    video_id: UUID = Field(
        validation_alias=AliasChoices("video_id", "videoId"),
        serialization_alias="videoId",
    )
    pipeline_version: int = Field(
        validation_alias=AliasChoices("pipeline_version", "pipelineVersion"),
        serialization_alias="pipelineVersion",
        ge=1,
    )
    provider: str = Field(min_length=1, max_length=128)
    language: LanguageCode
    text: str = Field(max_length=1_000_000)
    duration_seconds: float = Field(
        validation_alias=AliasChoices("duration_seconds", "durationSeconds"),
        serialization_alias="durationSeconds",
        ge=0,
    )
    confidence: ConfidenceScore | None = None
    segments: tuple[TranscriptionSegment, ...] = Field(min_length=0)
    created_at: datetime = Field(
        default_factory=lambda: datetime.now(UTC),
        validation_alias=AliasChoices("created_at", "createdAt"),
        serialization_alias="createdAt",
    )

    @model_validator(mode="after")
    def validate_segments(self) -> "TranscriptionResult":
        if not isfinite(self.duration_seconds):
            raise ValueError("transcription duration must be finite")
        previous_end = 0.0
        for segment in self.segments:
            if segment.end_seconds > self.duration_seconds:
                raise ValueError("segment timestamps must be inside the transcription duration")
            if segment.start_seconds < previous_end:
                raise ValueError("segment timestamps must be ordered")
            previous_end = segment.end_seconds
        return self


# These names make the boundary models discoverable without duplicating schemas.
TranscriptionLanguage = LanguageCode
TranscriptionConfidence = ConfidenceScore
ProviderWord = TranscriptionWord
ProviderSegment = TranscriptionSegment
