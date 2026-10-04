import re
from pathlib import PurePosixPath
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator


class ValidateVideoCommand(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    video_id: Annotated[UUID, Field(alias="videoId")]
    object_key: Annotated[str, Field(alias="objectKey", min_length=1, max_length=512)]
    original_filename: Annotated[str, Field(alias="originalFilename", min_length=1, max_length=255)]
    declared_content_type: Annotated[
        str, Field(alias="declaredContentType", min_length=1, max_length=127)
    ]
    declared_size_bytes: Annotated[int, Field(alias="declaredSizeBytes", gt=0)]
    worker_priority: Annotated[int, Field(alias="workerPriority", ge=0, le=10)] = 0

    @field_validator("object_key")
    @classmethod
    def validate_object_key(cls, value: str) -> str:
        path = PurePosixPath(value)
        if (
            not re.fullmatch(r"users/[A-Za-z0-9][A-Za-z0-9._/-]{0,510}", value)
            or "\\" in value
            or "//" in value
            or any(part in {".", ".."} for part in path.parts)
        ):
            raise ValueError("object key must be an owner-scoped safe path")
        return value

    @field_validator("original_filename")
    @classmethod
    def validate_original_filename(cls, value: str) -> str:
        if (
            value in {".", ".."}
            or "/" in value
            or "\\" in value
            or any(ord(character) < 32 or ord(character) == 127 for character in value)
        ):
            raise ValueError("original filename must not contain path components")
        return value


class VideoValidationResult(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    video_id: Annotated[UUID, Field(alias="videoId")]
    object_key: Annotated[str, Field(alias="objectKey")]
    original_filename: Annotated[str, Field(alias="originalFilename")]
    status: Literal["READY", "REJECTED"]
    failure_code: Annotated[str | None, Field(alias="failureCode")] = None
    actual_size_bytes: Annotated[int, Field(alias="actualSizeBytes")]
    duration_seconds: Annotated[float | None, Field(alias="durationSeconds")] = None
    width: int | None = None
    height: int | None = None
    frame_rate: Annotated[float | None, Field(alias="frameRate")] = None
    has_audio: Annotated[bool | None, Field(alias="hasAudio")] = None
    video_codec: Annotated[str | None, Field(alias="videoCodec")] = None
    audio_codec: Annotated[str | None, Field(alias="audioCodec")] = None
