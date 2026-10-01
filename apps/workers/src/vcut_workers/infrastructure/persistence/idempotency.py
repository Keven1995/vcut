import asyncio
from typing import Any

import psycopg

from vcut_workers.config import WorkerSettings
from vcut_workers.worker.idempotency import (
    IdempotencyClaim,
    IdempotencyClaimStatus,
)


class PostgresIdempotencyStore:
    """Durable idempotency claims shared by worker processes."""

    def __init__(self, settings: WorkerSettings, stale_after_seconds: int = 900) -> None:
        if stale_after_seconds < 1:
            raise ValueError("stale_after_seconds must be positive")
        self._settings = settings
        self._stale_after_seconds = stale_after_seconds

    async def claim(self, key: str) -> IdempotencyClaim:
        return await asyncio.to_thread(self._claim, key)

    async def complete(self, key: str, result_json: bytes) -> None:
        await asyncio.to_thread(self._complete, key, result_json)

    async def release(self, key: str) -> None:
        await asyncio.to_thread(self._release, key)

    def _claim(self, key: str) -> IdempotencyClaim:
        with self._connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    INSERT INTO worker_idempotency
                        (idempotency_key, status, claimed_at, updated_at)
                    VALUES (%s, 'IN_PROGRESS', now(), now())
                    ON CONFLICT (idempotency_key) DO NOTHING
                    """,
                    (key,),
                )
                if cursor.rowcount == 1:
                    return IdempotencyClaim(IdempotencyClaimStatus.CLAIMED)

                cursor.execute(
                    """
                    UPDATE worker_idempotency
                    SET claimed_at = now(), updated_at = now()
                    WHERE idempotency_key = %s
                      AND status = 'IN_PROGRESS'
                      AND claimed_at < now() - (%s * interval '1 second')
                    """,
                    (key, self._stale_after_seconds),
                )
                if cursor.rowcount == 1:
                    return IdempotencyClaim(IdempotencyClaimStatus.CLAIMED)

                cursor.execute(
                    "SELECT status, result_payload FROM worker_idempotency WHERE idempotency_key = %s",
                    (key,),
                )
                record = cursor.fetchone()
                if record is None:
                    raise RuntimeError("idempotency record disappeared during claim")
                status, result_payload = record
                if status == IdempotencyClaimStatus.COMPLETED.value:
                    return IdempotencyClaim(
                        IdempotencyClaimStatus.COMPLETED,
                        None if result_payload is None else str(result_payload).encode("utf-8"),
                    )
                return IdempotencyClaim(IdempotencyClaimStatus.IN_PROGRESS)

    def _complete(self, key: str, result_json: bytes) -> None:
        with self._connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    UPDATE worker_idempotency
                    SET status = 'COMPLETED', result_payload = %s, updated_at = now()
                    WHERE idempotency_key = %s AND status = 'IN_PROGRESS'
                    """,
                    (result_json.decode("utf-8"), key),
                )
                if cursor.rowcount != 1:
                    raise ValueError("idempotency key was not claimed")

    def _release(self, key: str) -> None:
        with self._connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    "DELETE FROM worker_idempotency WHERE idempotency_key = %s AND status = 'IN_PROGRESS'",
                    (key,),
                )

    def _connect(self) -> psycopg.Connection[Any]:
        return psycopg.connect(
            host=self._settings.postgres_host,
            port=self._settings.postgres_port,
            dbname=self._settings.postgres_database,
            user=self._settings.postgres_username,
            password=self._settings.postgres_password,
            connect_timeout=5,
        )
