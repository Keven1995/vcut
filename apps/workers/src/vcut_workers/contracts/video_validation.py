from typing import Literal
from uuid import UUID

from pydantic import BaseModel, Field


class ValidateVideoCommand(BaseModel):
    video_id: UUID
    object_key: str = Field(min_length=1, max_length=512)
    original_filename: str = Field(min_length=1, max_length=255)
    declared_content_type: str = Field(min_length=1, max_length=127)
    declared_size_bytes: int = Field(gt=0)


class VideoValidationResult(BaseModel):
    video_id: UUID
    object_key: str
    original_filename: str
    status: Literal["READY", "REJECTED"]
    failure_code: str | None = None
    actual_size_bytes: int
    duration_seconds: float | None = None
    width: int | None = None
    height: int | None = None
    frame_rate: float | None = None
    has_audio: bool | None = None
    video_codec: str | None = None
    audio_codec: str | None = None
