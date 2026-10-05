import psycopg

from vcut_workers.config import WorkerSettings
from vcut_workers.contracts.messaging import MessageEnvelope

_ELIGIBILITY_QUERIES: dict[str, tuple[str, str]] = {
    "VIDEO_VALIDATION": (
        "SELECT 1 FROM jobs WHERE id = %s AND status IN ('QUEUED', 'PROCESSING')",
        "job_id",
    ),
    "TRANSCRIPTION": (
        "SELECT 1 FROM transcriptions WHERE id = %s "
        "AND status IN ('QUEUED', 'PROCESSING', 'RETRYING')",
        "job_id",
    ),
    "CLIP_ANALYSIS": (
        "SELECT 1 FROM clip_analysis_runs WHERE id = %s "
        "AND status IN ('QUEUED', 'PROCESSING', 'RETRYING')",
        "job_id",
    ),
    "CLIP_GENERATION": (
        "SELECT 1 FROM clips WHERE id = %s AND status IN ('QUEUED', 'PROCESSING')",
        "resource_id",
    ),
    "FINAL_RENDER": (
        "SELECT 1 FROM clip_renders WHERE id = %s AND status IN ('QUEUED', 'PROCESSING')",
        "job_id",
    ),
}


class PostgresJobEligibilityStore:
    """Prevent cancelled or completed resources from starting delayed commands."""

    def __init__(self, settings: WorkerSettings) -> None:
        self._settings = settings

    def can_process(self, envelope: MessageEnvelope) -> bool:
        eligibility = _ELIGIBILITY_QUERIES.get(envelope.operation)
        if eligibility is None:
            return False
        query, identity_field = eligibility
        resource_id = envelope.resource_id if identity_field == "resource_id" else envelope.job_id
        with psycopg.connect(
            host=self._settings.postgres_host,
            port=self._settings.postgres_port,
            dbname=self._settings.postgres_database,
            user=self._settings.postgres_username,
            password=self._settings.postgres_password,
            connect_timeout=5,
        ) as connection:
            with connection.cursor() as cursor:
                cursor.execute(query, (resource_id,))
                return cursor.fetchone() is not None


__all__ = ["PostgresJobEligibilityStore"]
