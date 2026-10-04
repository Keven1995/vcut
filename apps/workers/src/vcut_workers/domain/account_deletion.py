from dataclasses import dataclass


@dataclass(frozen=True)
class AccountDeletionReport:
    claimed: int
    completed: int
    failed: int


__all__ = ["AccountDeletionReport"]
