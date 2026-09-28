from datetime import datetime
from enum import StrEnum
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator


class MessageKind(StrEnum):
    COMMAND = "COMMAND"
    EVENT = "EVENT"


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
