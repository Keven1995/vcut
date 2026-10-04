from typing import Any
from uuid import UUID

import psycopg

from vcut_workers.config import WorkerSettings


class PostgresAccountDeletionStore:
    """Finalize account erasure after its grace period and object cleanup."""

    def __init__(self, settings: WorkerSettings) -> None:
        self._settings = settings

    def claim_ready_deletions(self, *, limit: int) -> tuple[UUID, ...]:
        if limit < 1:
            raise ValueError("limit must be positive")
        with self._connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    WITH candidates AS (
                        SELECT request.id
                        FROM account_deletion_requests AS request
                        WHERE (
                            (request.status = 'PENDING' AND request.delete_after <= now())
                            OR (request.status = 'PROCESSING'
                                AND request.claimed_at <= now() - interval '5 minutes')
                        )
                        AND NOT EXISTS (
                            SELECT 1 FROM retained_objects AS object
                            WHERE object.user_id = request.user_id
                              AND object.retention_status <> 'DELETED'
                        )
                        AND NOT EXISTS (
                            SELECT 1 FROM jobs AS job
                            WHERE job.user_id = request.user_id
                              AND job.status IN ('QUEUED', 'PROCESSING')
                        )
                        AND NOT EXISTS (
                            SELECT 1 FROM worker_idempotency AS idempotency
                            JOIN videos AS video
                              ON idempotency.idempotency_key LIKE video.id::text || ':%'
                            WHERE video.user_id = request.user_id
                              AND idempotency.status = 'IN_PROGRESS'
                              AND idempotency.claimed_at > now() - interval '15 minutes'
                        )
                        ORDER BY request.delete_after, request.requested_at
                        FOR UPDATE SKIP LOCKED
                        LIMIT %s
                    )
                    UPDATE account_deletion_requests AS request
                    SET status = 'PROCESSING', attempt_count = attempt_count + 1,
                        claimed_at = now(), last_failure_code = NULL
                    FROM candidates
                    WHERE request.id = candidates.id
                    RETURNING request.user_id
                    """,
                    (limit,),
                )
                return tuple(UUID(str(row[0])) for row in cursor.fetchall() if row[0] is not None)

    def erase_account_data(self, user_id: UUID) -> None:
        with self._connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    SELECT id FROM account_deletion_requests
                    WHERE user_id = %s AND status = 'PROCESSING'
                      AND delete_after <= now()
                    FOR UPDATE
                    """,
                    (user_id,),
                )
                request = cursor.fetchone()
                if request is None:
                    raise ValueError("account deletion request is no longer claimable")

                cursor.execute(
                    """
                    SELECT 1 FROM retained_objects
                    WHERE user_id = %s AND retention_status <> 'DELETED'
                    LIMIT 1
                    """,
                    (user_id,),
                )
                if cursor.fetchone() is not None:
                    raise ValueError("account still has retained media objects")

                cursor.execute(
                    """
                    SELECT 1 FROM jobs AS job
                    WHERE job.user_id = %s AND job.status IN ('QUEUED', 'PROCESSING')
                    UNION ALL
                    SELECT 1 FROM worker_idempotency AS idempotency
                    JOIN videos AS video
                      ON idempotency.idempotency_key LIKE video.id::text || ':%'
                    WHERE video.user_id = %s AND idempotency.status = 'IN_PROGRESS'
                      AND idempotency.claimed_at > now() - interval '15 minutes'
                    LIMIT 1
                    """,
                    (user_id, user_id),
                )
                if cursor.fetchone() is not None:
                    raise ValueError("account still has active media jobs")

                cursor.execute(
                    """
                    UPDATE subscriptions
                    SET user_id = NULL, status = 'CANCELED', cancel_at_period_end = FALSE,
                        provider_customer_id = NULL, provider_subscription_id = NULL
                    WHERE user_id = %s
                    """,
                    (user_id,),
                )
                cursor.execute(
                    """
                    UPDATE billing_events
                    SET user_id = NULL, provider_event_id = 'erased-' || id::text,
                        checkout_session_id = NULL
                    WHERE user_id = %s
                    """,
                    (user_id,),
                )
                cursor.execute(
                    "UPDATE billing_ledger_entries SET user_id = NULL WHERE user_id = %s",
                    (user_id,),
                )
                cursor.execute(
                    """
                    UPDATE security_audit_events
                    SET actor_user_id = NULL, correlation_id = NULL
                    WHERE actor_user_id = %s
                    """,
                    (user_id,),
                )
                cursor.execute(
                    """
                    DELETE FROM outbox_messages
                    WHERE aggregate_type = 'JOB'
                      AND aggregate_id IN (SELECT id FROM jobs WHERE user_id = %s)
                    """,
                    (user_id,),
                )
                cursor.execute(
                    """
                    DELETE FROM worker_idempotency AS idempotency
                    WHERE EXISTS (
                        SELECT 1 FROM videos AS video
                        WHERE video.user_id = %s
                          AND idempotency.idempotency_key LIKE video.id::text || ':%'
                    )
                    """,
                    (user_id,),
                )
                cursor.execute("DELETE FROM retained_objects WHERE user_id = %s", (user_id,))
                cursor.execute(
                    """
                    UPDATE account_deletion_requests
                    SET status = 'COMPLETED', completed_at = now(), claimed_at = NULL,
                        last_failure_code = NULL
                    WHERE user_id = %s AND status = 'PROCESSING'
                    """,
                    (user_id,),
                )
                cursor.execute("DELETE FROM users WHERE id = %s", (user_id,))
                if cursor.rowcount != 1:
                    raise ValueError("account record was not found during erasure")

    def release_deletion(self, user_id: UUID, failure_code: str) -> None:
        if not failure_code.isupper() or not failure_code.replace("_", "").isalnum():
            raise ValueError("failure_code must be a sanitized code")
        with self._connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    UPDATE account_deletion_requests
                    SET status = 'PENDING', claimed_at = NULL, last_failure_code = %s
                    WHERE user_id = %s AND status = 'PROCESSING'
                    """,
                    (failure_code[:64], user_id),
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


__all__ = ["PostgresAccountDeletionStore"]
