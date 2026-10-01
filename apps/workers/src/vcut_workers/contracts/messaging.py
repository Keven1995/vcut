from datetime import datetime
from enum import StrEnum
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator


class MessageKind(StrEnum):
    COMMAND = "COMMAND"
    EVENT = "EVENT"


class StageRunStatus(StrEnum):
    PROCESSING = "PROCESSING"
    RETRYING = "RETRYING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class RetryMetadata(BaseModel):
    model_config = ConfigDict(extra="forbid")

    attempt: int = Field(ge=1)
    max_attempts: int = Field(ge=1)
    next_attempt: int = Field(ge=1)
    backoff_seconds: float = Field(ge=0)
    error_code: str = Field(min_length=1, max_length=128)
    error_message: str = Field(min_length=1, max_length=1_000)


class StageRunUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    job_id: UUID
    resource_id: UUID
    operation: str = Field(min_length=1, max_length=128)
    version: int = Field(ge=1)
    correlation_id: UUID
    attempt: int = Field(ge=1)
    status: StageRunStatus
    progress: float | None = Field(default=None, ge=0, le=100)
    error_code: str | None = Field(default=None, max_length=128)
    error_message: str | None = Field(default=None, max_length=1_000)


class MessageEnvelope(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    kind: MessageKind
    event_id: UUID = Field(alias="eventId")
    event_type: str = Field(alias="eventType", min_length=1)
    event_version: int = Field(alias="eventVersion", ge=1)
    job_id: UUID = Field(alias="jobId")
    resource_id: UUID = Field(alias="resourceId")
    operation: str = Field(min_length=1)
    version: int = Field(ge=1)
    correlation_id: UUID = Field(alias="correlationId")
    attempt: int = Field(ge=1)
    occurred_at: datetime = Field(alias="occurredAt")
    data: dict[str, object]

    @field_validator("event_version", "version")
    @classmethod
    def require_supported_version(cls, value: int) -> int:
        if value != 1:
            raise ValueError("only message version 1 is supported")
        return value


MessageVersion = Literal[1]
