from pydantic import BaseModel, Field


class ClipInput(BaseModel):
    start_seconds: float = Field(ge=0)
    end_seconds: float = Field(gt=0)


def parse_clip(payload: object) -> ClipInput:
    if not isinstance(payload, dict):
        raise TypeError("clip payload must be an object")

    return ClipInput.model_validate(payload)
