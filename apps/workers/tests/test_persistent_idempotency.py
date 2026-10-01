import asyncio
import threading
from typing import Any
from unittest.mock import patch

from vcut_workers.config import WorkerSettings
from vcut_workers.infrastructure.persistence.idempotency import PostgresIdempotencyStore
from vcut_workers.worker.idempotency import IdempotencyClaimStatus


class FakeDatabase:
    def __init__(self) -> None:
        self.records: dict[str, tuple[str, str | None]] = {}
        self.lock = threading.Lock()


class FakeCursor:
    def __init__(self, database: FakeDatabase) -> None:
        self.database = database
        self.rowcount = 0
        self._record: tuple[str, str | None] | None = None

    def __enter__(self) -> "FakeCursor":
        return self

    def __exit__(self, *_args: object) -> None:
        return None

    def execute(self, query: str, parameters: tuple[Any, ...]) -> None:
        with self.database.lock:
            if query.lstrip().startswith("INSERT"):
                key = parameters[0]
                self.rowcount = 0 if key in self.database.records else 1
                self.database.records.setdefault(key, ("IN_PROGRESS", None))
            elif "SET claimed_at" in query:
                self.rowcount = 0
            elif query.lstrip().startswith("SELECT"):
                self._record = self.database.records.get(parameters[0])
                self.rowcount = 1 if self._record is not None else 0
            elif "SET status = 'COMPLETED'" in query:
                key = parameters[1]
                if self.database.records.get(key) == ("IN_PROGRESS", None):
                    self.database.records[key] = ("COMPLETED", parameters[0])
                    self.rowcount = 1
                else:
                    self.rowcount = 0
            elif query.lstrip().startswith("DELETE"):
                key = parameters[0]
                self.rowcount = 1 if key in self.database.records else 0
                self.database.records.pop(key, None)

    def fetchone(self) -> tuple[str, str | None] | None:
        return self._record


class FakeConnection:
    def __init__(self, database: FakeDatabase) -> None:
        self.database = database

    def __enter__(self) -> "FakeConnection":
        return self

    def __exit__(self, *_args: object) -> None:
        return None

    def cursor(self) -> FakeCursor:
        return FakeCursor(self.database)


def settings() -> WorkerSettings:
    return WorkerSettings(
        postgres_host="localhost",
        postgres_port=15432,
        postgres_database="vcut",
        postgres_username="vcut",
        postgres_password="test",
    )


def test_persistent_idempotency_survives_new_store_instance() -> None:
    database = FakeDatabase()

    with patch(
        "vcut_workers.infrastructure.persistence.idempotency.psycopg.connect",
        side_effect=lambda **_kwargs: FakeConnection(database),
    ):
        first = PostgresIdempotencyStore(settings())
        second = PostgresIdempotencyStore(settings())
        first_claim = asyncio.run(first.claim("video:VIDEO_VALIDATION:1"))
        in_progress_claim = asyncio.run(second.claim("video:VIDEO_VALIDATION:1"))
        asyncio.run(first.complete("video:VIDEO_VALIDATION:1", b'{"status":"READY"}'))
        completed_claim = asyncio.run(second.claim("video:VIDEO_VALIDATION:1"))

    assert first_claim.status is IdempotencyClaimStatus.CLAIMED
    assert in_progress_claim.status is IdempotencyClaimStatus.IN_PROGRESS
    assert completed_claim.status is IdempotencyClaimStatus.COMPLETED
    assert completed_claim.result_json == b'{"status":"READY"}'
