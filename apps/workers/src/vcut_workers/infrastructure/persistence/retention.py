from datetime import UTC, datetime, timedelta
from typing import Any
from uuid import UUID, uuid4

import psycopg

from vcut_workers.config import WorkerSettings
from vcut_workers.domain.retention import ExpiredObject, RetainedObjectKind


class PostgresRetentionStore:
    """Claim expiry rows safely and preserve their audit records after object deletion."""

    def __init__(self, settings: WorkerSettings) -> None:
        self._settings = settings

    def register(
        self,
        user_id: UUID,
        project_id: UUID,
        object_key: str,
        asset_type: RetainedObjectKind,
        size_bytes: int,
    ) -> None:
        if not object_key.startswith("users/") or ".." in object_key.split("/"):
            raise ValueError("retained object key must be owner-scoped")
        if size_bytes < 0:
            raise ValueError("size_bytes must not be negative")
        with self._connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    SELECT plan_code FROM subscriptions
                    WHERE user_id = %s AND status = 'ACTIVE'
                      AND period_start <= now() AND period_end > now()
                    ORDER BY period_end DESC LIMIT 1
                    """,
                    (user_id,),
                )
                subscription = cursor.fetchone()
                plan_code = str(subscription[0]) if subscription is not None else "FREE"
                retention_days = (
                    self._settings.retention_days_pro
                    if plan_code == "PRO"
                    else self._settings.retention_days_free
                ).get(asset_type.value)
                if retention_days is None or retention_days < 0:
                    raise ValueError("retention policy is missing for this asset type")
                now = datetime.now(UTC)
                expires_at = now + timedelta(days=retention_days)
                cursor.execute(
                    """
                    INSERT INTO retained_objects
                        (id, user_id, project_id, object_key, asset_type, size_bytes,
                         retention_status, expires_at, delete_attempts, created_at)
                    VALUES (%s, %s, %s, %s, %s, %s, 'RETAINED', %s, 0, %s)
                    ON CONFLICT (object_key) DO UPDATE SET
                        size_bytes = EXCLUDED.size_bytes,
                        asset_type = EXCLUDED.asset_type,
                        retention_status = 'RETAINED',
                        expires_at = EXCLUDED.expires_at,
                        delete_attempts = 0,
                        last_failure_code = NULL,
                        claimed_at = NULL,
                        deleted_at = NULL
                    """,
                    (
                        uuid4(),
                        user_id,
                        project_id,
                        object_key,
                        asset_type.value,
                        size_bytes,
                        expires_at,
                        now,
                    ),
                )

    def find_expired(self, *, limit: int) -> tuple[ExpiredObject, ...]:
        return self._find_expired(limit)

    def claim_expired(self, *, limit: int) -> tuple[ExpiredObject, ...]:
        return self._claim_expired(limit)

    def mark_deleted(self, object_id: UUID) -> None:
        self._mark_deleted(object_id)

    def record_delete_failure(self, object_id: UUID, failure_code: str) -> None:
        self._record_delete_failure(object_id, failure_code)

    def _find_expired(self, limit: int) -> tuple[ExpiredObject, ...]:
        with self._connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    SELECT id, object_key, delete_attempts FROM retained_objects
                    WHERE (retention_status = 'RETAINED' AND expires_at <= now())
                       OR (retention_status = 'DELETE_PENDING' AND last_failure_code IS NOT NULL
                           AND (claimed_at IS NULL OR claimed_at <= now() - interval '5 minutes'))
                    ORDER BY expires_at
                    LIMIT %s
                    """,
                    (limit,),
                )
                return tuple(_expired(row) for row in cursor.fetchall())

    def _claim_expired(self, limit: int) -> tuple[ExpiredObject, ...]:
        with self._connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    WITH candidates AS (
                        SELECT id FROM retained_objects
                        WHERE (retention_status = 'RETAINED' AND expires_at <= now())
                           OR (retention_status = 'DELETE_PENDING' AND last_failure_code IS NOT NULL
                               AND (claimed_at IS NULL OR claimed_at <= now() - interval '5 minutes'))
                        ORDER BY expires_at
                        FOR UPDATE SKIP LOCKED
                        LIMIT %s
                    )
                    UPDATE retained_objects AS object
                    SET retention_status = 'DELETE_PENDING',
                        delete_attempts = delete_attempts + 1,
                        last_failure_code = NULL,
                        claimed_at = now()
                    FROM candidates
                    WHERE object.id = candidates.id
                    RETURNING object.id, object.object_key, object.delete_attempts
                    """,
                    (limit,),
                )
                return tuple(_expired(row) for row in cursor.fetchall())

    def _mark_deleted(self, object_id: UUID) -> None:
        with self._connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    UPDATE retained_objects
                    SET retention_status = 'DELETED', deleted_at = now(),
                        claimed_at = NULL, last_failure_code = NULL
                    WHERE id = %s AND retention_status = 'DELETE_PENDING'
                    """,
                    (object_id,),
                )
                if cursor.rowcount != 1:
                    raise ValueError("retained object is no longer pending deletion")

    def _record_delete_failure(self, object_id: UUID, failure_code: str) -> None:
        if not failure_code.isupper() or not failure_code.replace("_", "").isalnum():
            raise ValueError("failure_code must be a sanitized code")
        with self._connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    UPDATE retained_objects
                    SET last_failure_code = %s, claimed_at = now()
                    WHERE id = %s AND retention_status = 'DELETE_PENDING'
                    """,
                    (failure_code[:64], object_id),
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


def _expired(row: tuple[object, ...]) -> ExpiredObject:
    object_id, object_key, attempts = row
    if not isinstance(attempts, int):
        raise TypeError("delete_attempts must be an integer")
    return ExpiredObject(
        id=UUID(str(object_id)),
        object_key=str(object_key),
        delete_attempts=attempts,
    )


__all__ = ["PostgresRetentionStore"]
