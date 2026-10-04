import logging
import time

from vcut_workers.application.account_deletion import CompleteDueAccountDeletionsUseCase
from vcut_workers.application.retention_cleanup import CleanupExpiredObjectsUseCase
from vcut_workers.config import WorkerSettings
from vcut_workers.domain.retention import RetentionCleanupReport
from vcut_workers.infrastructure.persistence.account_deletion import PostgresAccountDeletionStore
from vcut_workers.infrastructure.persistence.retention import PostgresRetentionStore
from vcut_workers.infrastructure.storage.s3 import S3ObjectStorage

LOGGER = logging.getLogger(__name__)


class RetentionCleanupWorker:
    def __init__(self, settings: WorkerSettings) -> None:
        self._settings = settings
        self._use_case = CleanupExpiredObjectsUseCase(
            PostgresRetentionStore(settings),
            S3ObjectStorage(settings),
            batch_size=settings.retention_cleanup_batch_size,
        )
        self._account_deletion_use_case = CompleteDueAccountDeletionsUseCase(
            PostgresAccountDeletionStore(settings),
            batch_size=settings.retention_cleanup_batch_size,
        )

    def run_once(self) -> RetentionCleanupReport:
        report = self._use_case.execute(dry_run=self._settings.retention_cleanup_dry_run)
        deletion_report = (
            self._account_deletion_use_case.execute()
            if not self._settings.retention_cleanup_dry_run
            else None
        )
        LOGGER.info(
            "retention_cleanup_completed dryRun=%s scanned=%s deleted=%s failed=%s",
            report.dry_run,
            report.scanned,
            report.deleted,
            report.failed,
        )
        if deletion_report is not None:
            LOGGER.info(
                "account_deletion_cleanup_completed claimed=%s completed=%s failed=%s",
                deletion_report.claimed,
                deletion_report.completed,
                deletion_report.failed,
            )
        return report

    def run_forever(self) -> None:
        while True:
            try:
                self.run_once()
            except Exception as error:
                LOGGER.warning("retention_cleanup_failed error=%s", type(error).__name__)
            time.sleep(self._settings.retention_cleanup_interval_seconds)


__all__ = ["RetentionCleanupWorker"]
