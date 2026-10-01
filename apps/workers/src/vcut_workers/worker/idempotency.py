import asyncio
from dataclasses import dataclass
from enum import StrEnum
from typing import Protocol
from uuid import UUID

from vcut_workers.contracts.messaging import MessageEnvelope


class IdempotencyClaimStatus(StrEnum):
    CLAIMED = "CLAIMED"
    IN_PROGRESS = "IN_PROGRESS"
    COMPLETED = "COMPLETED"


@dataclass(frozen=True)
class IdempotencyClaim:
    status: IdempotencyClaimStatus
    result_json: bytes | None = None


class IdempotencyStore(Protocol):
    async def claim(self, key: str) -> IdempotencyClaim:
        """Atomically claim a key before executing a command."""

    async def complete(self, key: str, result_json: bytes) -> None:
        """Persist a successful result for duplicate deliveries."""

    async def release(self, key: str) -> None:
        """Release an unfinished claim so a retry can execute it."""


def idempotency_key(resource_id: UUID, operation: str, version: int) -> str:
    if not operation:
        raise ValueError("operation must not be empty")
    if version < 1:
        raise ValueError("version must be positive")
    return f"{resource_id}:{operation}:{version}"


def idempotency_key_for(envelope: MessageEnvelope) -> str:
    return idempotency_key(envelope.resource_id, envelope.operation, envelope.version)


class InMemoryIdempotencyStore:
    """Small process-local adapter used by tests and local worker experiments."""

    def __init__(self) -> None:
        self._records: dict[str, bytes | None] = {}
        self._lock = asyncio.Lock()

    async def claim(self, key: str) -> IdempotencyClaim:
        async with self._lock:
            if key not in self._records:
                self._records[key] = None
                return IdempotencyClaim(IdempotencyClaimStatus.CLAIMED)
            result_json = self._records[key]
            if result_json is None:
                return IdempotencyClaim(IdempotencyClaimStatus.IN_PROGRESS)
            return IdempotencyClaim(IdempotencyClaimStatus.COMPLETED, result_json)

    async def complete(self, key: str, result_json: bytes) -> None:
        async with self._lock:
            if key not in self._records or self._records[key] is not None:
                raise ValueError("idempotency key was not claimed")
            self._records[key] = result_json

    async def release(self, key: str) -> None:
        async with self._lock:
            if self._records.get(key) is None:
                self._records.pop(key, None)
