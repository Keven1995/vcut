from pathlib import Path
from uuid import UUID

from vcut_workers.application.retention_cleanup import CleanupExpiredObjectsUseCase
from vcut_workers.domain.media import ObjectMetadata
from vcut_workers.domain.retention import ExpiredObject

OBJECT_ID = UUID("11111111-1111-4111-8111-111111111111")


class FakeRetentionStore:
    def __init__(self) -> None:
        self.deleted: list[UUID] = []
        self.failures: list[tuple[UUID, str]] = []

    def find_expired(self, *, limit: int) -> tuple[ExpiredObject, ...]:
        return (ExpiredObject(OBJECT_ID, "users/u/projects/p/audio/a.wav", 0),)[:limit]

    def claim_expired(self, *, limit: int) -> tuple[ExpiredObject, ...]:
        return self.find_expired(limit=limit)

    def mark_deleted(self, object_id: UUID) -> None:
        self.deleted.append(object_id)

    def record_delete_failure(self, object_id: UUID, failure_code: str) -> None:
        self.failures.append((object_id, failure_code))


class FakeObjectStorage:
    def __init__(self, *, fail: bool = False) -> None:
        self.fail = fail
        self.deleted: list[str] = []

    def delete(self, object_key: str) -> None:
        if self.fail:
            raise RuntimeError("storage error")
        self.deleted.append(object_key)

    def exists(self, object_key: str) -> bool:
        return object_key in self.deleted

    def head(self, object_key: str) -> ObjectMetadata | None:
        del object_key
        return None

    def download(self, object_key: str, destination: Path) -> None:
        del object_key, destination
        raise NotImplementedError

    def upload(self, source: Path, object_key: str, content_type: str) -> ObjectMetadata:
        del source, object_key, content_type
        raise NotImplementedError


def test_dry_run_reports_expired_objects_without_deleting_or_claiming() -> None:
    store = FakeRetentionStore()
    storage = FakeObjectStorage()
    use_case = CleanupExpiredObjectsUseCase(store, storage, batch_size=10)

    report = use_case.execute(dry_run=True)

    assert report.dry_run is True
    assert report.scanned == 1
    assert report.deleted == 0
    assert storage.deleted == []
    assert store.deleted == []


def test_cleanup_deletes_objects_and_marks_audit_rows_deleted() -> None:
    store = FakeRetentionStore()
    storage = FakeObjectStorage()
    use_case = CleanupExpiredObjectsUseCase(store, storage, batch_size=10)

    report = use_case.execute()

    assert report.deleted == 1
    assert storage.deleted == ["users/u/projects/p/audio/a.wav"]
    assert store.deleted == [OBJECT_ID]


def test_cleanup_records_failure_code_and_keeps_the_object_for_retry() -> None:
    store = FakeRetentionStore()
    storage = FakeObjectStorage(fail=True)
    use_case = CleanupExpiredObjectsUseCase(store, storage, batch_size=10)

    report = use_case.execute()

    assert report.failed == 1
    assert report.failure_codes == ("OBJECT_DELETE_FAILED",)
    assert store.failures == [(OBJECT_ID, "OBJECT_DELETE_FAILED")]
    assert store.deleted == []
