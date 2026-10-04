from uuid import UUID

import psycopg

from vcut_workers.config import WorkerSettings


class PostgresJobEligibilityStore:
    """Prevent cancelled or erased jobs from starting from delayed broker messages."""

    def __init__(self, settings: WorkerSettings) -> None:
        self._settings = settings

    def can_process(self, job_id: UUID) -> bool:
        with psycopg.connect(
            host=self._settings.postgres_host,
            port=self._settings.postgres_port,
            dbname=self._settings.postgres_database,
            user=self._settings.postgres_username,
            password=self._settings.postgres_password,
            connect_timeout=5,
        ) as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT 1 FROM jobs WHERE id = %s AND status IN ('QUEUED', 'PROCESSING')",
                    (job_id,),
                )
                return cursor.fetchone() is not None


__all__ = ["PostgresJobEligibilityStore"]
