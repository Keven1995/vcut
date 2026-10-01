import json
from pathlib import Path

import pytest

from vcut_workers.contracts import MessageEnvelope


def test_shared_event_fixture_round_trips_with_optional_fields() -> None:
    fixture_path = (
        Path(__file__).parents[3] / "tests" / "fixtures" / "events" / "video-uploaded-v1.json"
    )
    payload = json.loads(fixture_path.read_text(encoding="utf-8"))

    envelope = MessageEnvelope.model_validate(payload)
    serialized = envelope.model_dump(by_alias=True, mode="json")

    assert serialized["eventType"] == "VideoUploaded"
    assert serialized["data"]["optionalLabel"] == "source"
    assert "optionalEnvelopeField" not in serialized


def test_incompatible_version_is_rejected() -> None:
    with pytest.raises(ValueError, match="only message version 1 is supported"):
        MessageEnvelope.model_validate(
            {
                "kind": "EVENT",
                "eventId": "11111111-1111-4111-8111-111111111111",
                "eventType": "VideoUploaded",
                "eventVersion": 2,
                "jobId": "22222222-2222-4222-8222-222222222222",
                "resourceId": "33333333-3333-4333-8333-333333333333",
                "operation": "video.ingest",
                "version": 1,
                "correlationId": "44444444-4444-4444-8444-444444444444",
                "attempt": 1,
                "occurredAt": "2026-09-28T12:00:00Z",
                "data": {},
            }
        )
