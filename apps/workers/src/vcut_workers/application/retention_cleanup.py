import logging

from vcut_workers.application.ports import RetentionStore, WritableObjectStorage
from vcut_workers.domain.retention import RetentionCleanupReport

LOGGER = logging.getLogger(__name__)


class CleanupExpiredObjectsUseCase:
    def __init__(
        self,
        retention_store: RetentionStore,
        object_storage: WritableObjectStorage,
        *,
        batch_size: int = 100,
    ) -> None:
        if batch_size < 1:
            raise ValueError("batch_size must be positive")
        self._retention_store = retention_store
        self._object_storage = object_storage
        self._batch_size = batch_size

    def execute(self, *, dry_run: bool = False) -> RetentionCleanupReport:
        expired = (
            self._retention_store.find_expired(limit=self._batch_size)
            if dry_run
            else self._retention_store.claim_expired(limit=self._batch_size)
        )
        if dry_run:
            return RetentionCleanupReport(True, len(expired), 0, 0)

        deleted = 0
        failed = 0
        failure_codes: list[str] = []
        for item in expired:
            try:
                self._object_storage.delete(item.object_key)
                self._retention_store.mark_deleted(item.id)
                deleted += 1
            except Exception as error:
                self._retention_store.record_delete_failure(item.id, "OBJECT_DELETE_FAILED")
                LOGGER.warning(
                    "retention_delete_failed objectId=%s error=%s",
                    item.id,
                    type(error).__name__,
                )
                failure_codes.append("OBJECT_DELETE_FAILED")
                failed += 1
        return RetentionCleanupReport(False, len(expired), deleted, failed, tuple(failure_codes))


__all__ = ["CleanupExpiredObjectsUseCase"]
