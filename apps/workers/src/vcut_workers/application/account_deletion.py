import logging
from dataclasses import dataclass
from uuid import UUID

from vcut_workers.application.ports import AccountDeletionStore
from vcut_workers.domain.account_deletion import AccountDeletionReport

LOGGER = logging.getLogger(__name__)


@dataclass(frozen=True)
class CompleteDueAccountDeletionsUseCase:
    deletion_store: AccountDeletionStore
    batch_size: int = 100

    def __post_init__(self) -> None:
        if self.batch_size < 1:
            raise ValueError("batch_size must be positive")

    def execute(self) -> AccountDeletionReport:
        user_ids: tuple[UUID, ...] = self.deletion_store.claim_ready_deletions(
            limit=self.batch_size
        )
        completed = 0
        failed = 0
        for user_id in user_ids:
            try:
                self.deletion_store.erase_account_data(user_id)
                completed += 1
            except Exception as error:
                self.deletion_store.release_deletion(user_id, "ACCOUNT_ERASURE_FAILED")
                LOGGER.warning("account_erasure_failed errorType=%s", type(error).__name__)
                failed += 1
        return AccountDeletionReport(len(user_ids), completed, failed)


__all__ = ["CompleteDueAccountDeletionsUseCase"]
