from uuid import UUID

from vcut_workers.application.account_deletion import CompleteDueAccountDeletionsUseCase

USER_ID = UUID("11111111-1111-4111-8111-111111111111")


class FakeAccountDeletionStore:
    def __init__(self, user_ids: tuple[UUID, ...], *, failed: bool = False) -> None:
        self.user_ids = user_ids
        self.failed = failed
        self.erased: list[UUID] = []
        self.released: list[tuple[UUID, str]] = []

    def claim_ready_deletions(self, *, limit: int) -> tuple[UUID, ...]:
        return self.user_ids[:limit]

    def erase_account_data(self, user_id: UUID) -> None:
        if self.failed:
            raise RuntimeError("database unavailable")
        self.erased.append(user_id)

    def release_deletion(self, user_id: UUID, failure_code: str) -> None:
        self.released.append((user_id, failure_code))


def test_completes_only_claimed_deletions_and_reports_counts() -> None:
    store = FakeAccountDeletionStore((USER_ID,))

    report = CompleteDueAccountDeletionsUseCase(store).execute()

    assert report.claimed == 1
    assert report.completed == 1
    assert report.failed == 0
    assert store.erased == [USER_ID]


def test_releases_failed_deletion_for_retry_without_exposing_database_error() -> None:
    store = FakeAccountDeletionStore((USER_ID,), failed=True)

    report = CompleteDueAccountDeletionsUseCase(store).execute()

    assert report.claimed == 1
    assert report.completed == 0
    assert report.failed == 1
    assert store.released == [(USER_ID, "ACCOUNT_ERASURE_FAILED")]
