from typing import Any
from uuid import UUID

import psycopg

from vcut_workers.config import WorkerSettings
from vcut_workers.domain.vision import SceneInterval


class PostgresSceneIntervalStore:
    """Persist scene boundaries independently from the render artifact."""

    def __init__(self, settings: WorkerSettings) -> None:
        self._settings = settings

    def save(
        self,
        video_id: UUID,
        pipeline_version: int,
        intervals: tuple[SceneInterval, ...],
    ) -> None:
        if pipeline_version < 1:
            raise ValueError("pipeline_version must be positive")
        with self._connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    DELETE FROM video_scene_intervals
                    WHERE video_id = %s AND pipeline_version = %s
                    """,
                    (video_id, pipeline_version),
                )
                cursor.executemany(
                    """
                    INSERT INTO video_scene_intervals
                        (video_id, pipeline_version, scene_index, start_seconds,
                         end_seconds, confidence)
                    VALUES (%s, %s, %s, %s, %s, %s)
                    """,
                    [
                        (
                            video_id,
                            pipeline_version,
                            index,
                            interval.start_seconds,
                            interval.end_seconds,
                            interval.confidence,
                        )
                        for index, interval in enumerate(intervals)
                    ],
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


__all__ = ["PostgresSceneIntervalStore"]
