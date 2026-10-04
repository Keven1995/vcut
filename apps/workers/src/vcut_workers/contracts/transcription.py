from typing import Annotated
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field

from vcut_workers.domain.transcription import (
    ProviderSegment,
    ProviderWord,
    TranscriptionConfidence,
    TranscriptionLanguage,
    TranscriptionProviderResponse,
    TranscriptionResult,
)


class TranscribeAudioCommand(BaseModel):
    """Versioned worker command for an audio object already in storage."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    video_id: Annotated[UUID, Field(alias="videoId")]
    pipeline_version: Annotated[int, Field(alias="pipelineVersion", ge=1)]
    audio_object_key: Annotated[str, Field(alias="audioObjectKey", min_length=1, max_length=512)]
    worker_priority: Annotated[int, Field(alias="workerPriority", ge=0, le=10)] = 0
    language: TranscriptionLanguage | None = None


__all__ = [
    "ProviderSegment",
    "ProviderWord",
    "TranscribeAudioCommand",
    "TranscriptionConfidence",
    "TranscriptionLanguage",
    "TranscriptionProviderResponse",
    "TranscriptionResult",
]
