import pytest
from contract import ClipInput, parse_clip
from pydantic import ValidationError


def test_parse_clip_validates_external_payload() -> None:
    clip = parse_clip({"start_seconds": 1.5, "end_seconds": 4.0})

    assert clip == ClipInput(start_seconds=1.5, end_seconds=4.0)


def test_parse_clip_rejects_negative_start() -> None:
    with pytest.raises(ValidationError):
        parse_clip({"start_seconds": -1, "end_seconds": 4})
