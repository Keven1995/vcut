from math import isfinite
from typing import Annotated
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from vcut_workers.domain.clip_generation import (
    AspectRatio,
    CaptionAnimation,
    CaptionCue,
    CaptionPosition,
    CaptionPreset,
    CaptionStyle,
    CaptionTrack,
    ClipStatus,
    CropSettings,
    validate_object_key,
)


class ClipGenerationCommand(BaseModel):
    """Camel-case command published by the API for one immutable edit version."""

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    clip_id: Annotated[UUID, Field(alias="clipId")]
    user_id: Annotated[UUID, Field(alias="userId")]
    project_id: Annotated[UUID, Field(alias="projectId")]
    video_id: Annotated[UUID, Field(alias="videoId")]
    pipeline_version: Annotated[int, Field(alias="pipelineVersion", ge=1)]
    edit_version: Annotated[int, Field(alias="editVersion", ge=1)]
    source_object_key: Annotated[
        str, Field(alias="sourceObjectKey", min_length=1, max_length=512)
    ]
    output_object_key: Annotated[
        str, Field(alias="outputObjectKey", min_length=1, max_length=512)
    ]
    start_seconds: Annotated[float, Field(alias="startSeconds", ge=0)]
    end_seconds: Annotated[float, Field(alias="endSeconds", gt=0)]
    aspect_ratio: Annotated[AspectRatio, Field(alias="aspectRatio")]
    caption_preset: Annotated[CaptionPreset, Field(alias="captionPreset")]
    caption_style: Annotated[CaptionStyle, Field(alias="captionStyle")]
    caption_cues: Annotated[
        tuple[CaptionCue, ...], Field(alias="captionCues", min_length=0)
    ] = ()
    crop_settings: Annotated[CropSettings, Field(alias="cropSettings")] = Field(
        default_factory=CropSettings.centered
    )
    worker_priority: Annotated[int, Field(alias="workerPriority", ge=0, le=10)] = 0

    @field_validator("source_object_key", "output_object_key")
    @classmethod
    def validate_storage_key(cls, value: str) -> str:
        return validate_object_key(value)

    @model_validator(mode="after")
    def validate_interval_and_cues(self) -> "ClipGenerationCommand":
        if not isfinite(self.start_seconds) or not isfinite(self.end_seconds):
            raise ValueError("clip timestamps must be finite")
        if self.end_seconds <= self.start_seconds:
            raise ValueError("clip end must be greater than start")
        CaptionTrack(
            cues=self.caption_cues,
            duration_seconds=self.end_seconds - self.start_seconds,
        )
        return self

    @property
    def duration_seconds(self) -> float:
        return self.end_seconds - self.start_seconds

class ClipGenerationResult(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    clip_id: Annotated[UUID, Field(alias="clipId")]
    edit_version: Annotated[int, Field(alias="editVersion", ge=1)]
    status: ClipStatus
    output_object_key: Annotated[
        str | None, Field(alias="outputObjectKey", min_length=1, max_length=512)
    ] = None
    duration_seconds: Annotated[float, Field(alias="durationSeconds", ge=0)]
    width: int = Field(ge=0)
    height: int = Field(ge=0)
    aspect_ratio: Annotated[AspectRatio, Field(alias="aspectRatio")]
    error_code: Annotated[str | None, Field(alias="errorCode", pattern=r"^[A-Z0-9_]{1,128}$")] = None
    error_message: Annotated[str | None, Field(alias="errorMessage", min_length=1, max_length=1_000)] = None

    @field_validator("output_object_key")
    @classmethod
    def validate_output_key(cls, value: str | None) -> str | None:
        return None if value is None else validate_object_key(value)

    @model_validator(mode="after")
    def validate_status_payload(self) -> "ClipGenerationResult":
        if not isfinite(self.duration_seconds):
            raise ValueError("result duration must be finite")
        if self.status is ClipStatus.READY:
            if self.output_object_key is None:
                raise ValueError("READY result requires output_object_key")
            if self.duration_seconds <= 0 or self.width <= 0 or self.height <= 0:
                raise ValueError("READY result requires positive media metadata")
            if self.error_code is not None or self.error_message is not None:
                raise ValueError("READY result must not contain an error")
        elif self.error_code is None or self.error_message is None:
            raise ValueError("FAILED result requires error_code and error_message")
        return self


__all__ = [
    "AspectRatio",
    "CaptionAnimation",
    "CaptionCue",
    "CaptionPosition",
    "CaptionPreset",
    "CaptionStyle",
    "CaptionTrack",
    "ClipGenerationCommand",
    "ClipGenerationResult",
    "ClipStatus",
    "CropSettings",
]
