from datetime import UTC, datetime
from typing import Any
from unittest.mock import patch
from uuid import UUID, uuid4

import pytest

from vcut_workers.config import WorkerSettings
from vcut_workers.contracts.messaging import MessageEnvelope, MessageKind
from vcut_workers.infrastructure.persistence.job_eligibility import (
    PostgresJobEligibilityStore,
)


class FakeDatabase:
    def __init__(self, eligible: bool) -> None:
        self.eligible = eligible
        self.query: str | None = None
        self.parameters: tuple[Any, ...] | None = None


class FakeCursor:
    def __init__(self, database: FakeDatabase) -> None:
        self.database = database

    def __enter__(self) -> "FakeCursor":
        return self

    def __exit__(self, *_args: object) -> None:
        return None

    def execute(self, query: str, parameters: tuple[Any, ...]) -> None:
        self.database.query = query
        self.database.parameters = parameters

    def fetchone(self) -> tuple[int] | None:
        return (1,) if self.database.eligible else None


class FakeConnection:
    def __init__(self, database: FakeDatabase) -> None:
        self.database = database

    def __enter__(self) -> "FakeConnection":
        return self

    def __exit__(self, *_args: object) -> None:
        return None

    def cursor(self) -> FakeCursor:
        return FakeCursor(self.database)


def envelope(operation: str, *, job_id: UUID, resource_id: UUID) -> MessageEnvelope:
    return MessageEnvelope(
        kind=MessageKind.COMMAND,
        eventId=uuid4(),
        eventType="TestCommand",
        eventVersion=1,
        jobId=job_id,
        resourceId=resource_id,
        operation=operation,
        version=1,
        correlationId=uuid4(),
        attempt=1,
        occurredAt=datetime.now(UTC),
        data={},
    )


@pytest.mark.parametrize(
    ("operation", "table", "identity_field"),
    [
        ("VIDEO_VALIDATION", "jobs", "job_id"),
        ("TRANSCRIPTION", "transcriptions", "job_id"),
        ("CLIP_ANALYSIS", "clip_analysis_runs", "job_id"),
        ("CLIP_GENERATION", "clips", "resource_id"),
        ("FINAL_RENDER", "clip_renders", "job_id"),
    ],
)
def test_checks_active_record_for_command_operation(
    operation: str, table: str, identity_field: str
) -> None:
    job_id = uuid4()
    resource_id = uuid4()
    command = envelope(operation, job_id=job_id, resource_id=resource_id)
    database = FakeDatabase(eligible=True)

    with patch(
        "vcut_workers.infrastructure.persistence.job_eligibility.psycopg.connect",
        side_effect=lambda **_kwargs: FakeConnection(database),
    ):
        eligible = PostgresJobEligibilityStore(WorkerSettings()).can_process(command)

    expected_id = job_id if identity_field == "job_id" else resource_id
    assert eligible is True
    assert database.query is not None and f"FROM {table} " in database.query
    assert database.parameters == (expected_id,)


def test_returns_ineligible_when_transcription_is_not_active() -> None:
    command = envelope("TRANSCRIPTION", job_id=uuid4(), resource_id=uuid4())
    database = FakeDatabase(eligible=False)

    with patch(
        "vcut_workers.infrastructure.persistence.job_eligibility.psycopg.connect",
        side_effect=lambda **_kwargs: FakeConnection(database),
    ):
        eligible = PostgresJobEligibilityStore(WorkerSettings()).can_process(command)

    assert eligible is False
    assert database.query is not None and "FROM transcriptions " in database.query


def test_unknown_operation_is_not_eligible() -> None:
    command = envelope("UNKNOWN", job_id=uuid4(), resource_id=uuid4())

    assert PostgresJobEligibilityStore(WorkerSettings()).can_process(command) is False
