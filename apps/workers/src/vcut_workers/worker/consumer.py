import asyncio
import inspect
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from enum import StrEnum
from typing import Generic, Protocol, TypeVar

from pydantic import BaseModel, ValidationError

from vcut_workers.contracts.messaging import (
    MessageEnvelope,
    RetryMetadata,
    StageRunStatus,
    StageRunUpdate,
)
from vcut_workers.worker.errors import (
    ErrorClassification,
    ProcessingErrorInfo,
    classify_error,
)
from vcut_workers.worker.idempotency import (
    IdempotencyClaimStatus,
    IdempotencyStore,
    idempotency_key_for,
)
from vcut_workers.worker.retry import RetryPolicy

CommandModelT = TypeVar("CommandModelT", bound=BaseModel)
ResultModelT = TypeVar("ResultModelT", bound=BaseModel)
HandlerCommandT = TypeVar("HandlerCommandT", bound=BaseModel, contravariant=True)
HandlerResultT = TypeVar("HandlerResultT", bound=BaseModel, covariant=True)
ProgressCallback = Callable[[float], None]
ProgressHandler = Callable[
    [CommandModelT, ProgressCallback], ResultModelT | Awaitable[ResultModelT]
]


class CommandHandler(Protocol[HandlerCommandT, HandlerResultT]):
    def __call__(
        self, command: HandlerCommandT, /
    ) -> HandlerResultT | Awaitable[HandlerResultT]: ...


class MessageDelivery(Protocol):
    @property
    def body(self) -> bytes: ...

    @property
    def delivery_tag(self) -> str: ...

    async def ack(self) -> None:
        """Confirm a delivery after its result has been accepted."""

    async def reject(self, *, requeue: bool) -> None:
        """Reject a delivery when it cannot be acknowledged."""


class RetryPublisher(Protocol):
    async def publish_retry(self, body: bytes, metadata: RetryMetadata) -> None:
        """Publish a confirmed retry before the original delivery is acknowledged."""

    async def publish_dead_letter(self, body: bytes, error: ProcessingErrorInfo) -> None:
        """Publish a confirmed dead-letter message before acknowledging the original."""


class ResultPublisher(Protocol):
    async def publish_result(self, envelope: MessageEnvelope, result: BaseModel) -> None:
        """Publish a typed result before acknowledging the command."""


class StageRunUpdater(Protocol):
    async def update(self, update: StageRunUpdate) -> None:
        """Persist the worker-side stage state."""


class ConsumerStatus(StrEnum):
    SUCCEEDED = "SUCCEEDED"
    DUPLICATE = "DUPLICATE"
    IN_PROGRESS = "IN_PROGRESS"
    RETRY_SCHEDULED = "RETRY_SCHEDULED"
    FAILED = "FAILED"


@dataclass(frozen=True)
class ConsumerOutcome(Generic[ResultModelT]):
    status: ConsumerStatus
    result: ResultModelT | None = None
    error: ProcessingErrorInfo | None = None
    retry: RetryMetadata | None = None


class ConsumerBase(Generic[CommandModelT, ResultModelT]):
    """Broker-neutral command consumer with explicit manual acknowledgement."""

    def __init__(
        self,
        command_type: type[CommandModelT],
        result_type: type[ResultModelT],
        handler: CommandHandler[CommandModelT, ResultModelT],
        idempotency_store: IdempotencyStore,
        retry_policy: RetryPolicy | None = None,
        timeout_seconds: float = 300.0,
        retry_publisher: RetryPublisher | None = None,
        result_publisher: ResultPublisher | None = None,
        stage_run_updater: StageRunUpdater | None = None,
        progress_handler: ProgressHandler[CommandModelT, ResultModelT] | None = None,
    ) -> None:
        if timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be positive")
        self._command_type = command_type
        self._result_type = result_type
        self._handler = handler
        self._idempotency_store = idempotency_store
        self._retry_policy = retry_policy or RetryPolicy()
        self._timeout_seconds = timeout_seconds
        self._retry_publisher = retry_publisher
        self._result_publisher = result_publisher
        self._stage_run_updater = stage_run_updater
        self._progress_handler = progress_handler

    async def consume(self, delivery: MessageDelivery) -> ConsumerOutcome[ResultModelT]:
        try:
            envelope = MessageEnvelope.model_validate_json(delivery.body)
            command = self._command_type.model_validate(envelope.data)
        except ValidationError as error:
            error_info = ProcessingErrorInfo(
                ErrorClassification.PERMANENT,
                "INVALID_COMMAND",
                str(error)[:1_000],
            )
            await self._dead_letter(delivery, error_info)
            return ConsumerOutcome(ConsumerStatus.FAILED, error=error_info)

        key = idempotency_key_for(envelope)
        claim = await self._idempotency_store.claim(key)
        if claim.status is IdempotencyClaimStatus.COMPLETED:
            result = (
                self._result_type.model_validate_json(claim.result_json)
                if claim.result_json is not None
                else None
            )
            await delivery.ack()
            return ConsumerOutcome(ConsumerStatus.DUPLICATE, result=result)
        if claim.status is IdempotencyClaimStatus.IN_PROGRESS:
            await delivery.reject(requeue=True)
            return ConsumerOutcome(ConsumerStatus.IN_PROGRESS)

        try:
            await self._update_stage(envelope, StageRunStatus.PROCESSING, progress=0)
            result, progress_updates = await self._execute(command)
            result = self._result_type.model_validate(result)
            for progress_value in progress_updates:
                await self._update_stage(
                    envelope, StageRunStatus.PROCESSING, progress=progress_value
                )
            await self._update_stage(envelope, StageRunStatus.COMPLETED, progress=100)
            if self._result_publisher is not None:
                await self._result_publisher.publish_result(envelope, result)
        except Exception as error:
            error_info = classify_error(error)
            return await self._handle_failure(delivery, envelope, key, error_info)
        await self._idempotency_store.complete(key, result.model_dump_json().encode("utf-8"))
        await delivery.ack()
        return ConsumerOutcome(ConsumerStatus.SUCCEEDED, result=result)

    async def _execute(self, command: CommandModelT) -> tuple[ResultModelT, tuple[float, ...]]:
        progress_updates: list[float] = []
        last_progress = 0.0

        def report(progress: float) -> None:
            nonlocal last_progress
            if progress < 0 or progress > 100:
                raise ValueError("progress must be between 0 and 100")
            monotonic_progress = min(99.0, max(last_progress, progress))
            if monotonic_progress > last_progress:
                progress_updates.append(monotonic_progress)
                last_progress = monotonic_progress

        async def invoke() -> ResultModelT:
            if self._progress_handler is not None:
                progress_handler = self._progress_handler
                is_async_handler = inspect.iscoroutinefunction(progress_handler)
                value = (
                    progress_handler(command, report)
                    if is_async_handler
                    else await asyncio.to_thread(progress_handler, command, report)
                )
            else:
                command_handler = self._handler
                is_async_handler = inspect.iscoroutinefunction(
                    command_handler
                ) or inspect.iscoroutinefunction(command_handler.__call__)
                value = (
                    command_handler(command)
                    if is_async_handler
                    else await asyncio.to_thread(command_handler, command)
                )
            if inspect.isawaitable(value):
                return await value
            return value

        return await asyncio.wait_for(invoke(), timeout=self._timeout_seconds), tuple(progress_updates)

    async def _handle_failure(
        self,
        delivery: MessageDelivery,
        envelope: MessageEnvelope,
        key: str,
        error: ProcessingErrorInfo,
    ) -> ConsumerOutcome[ResultModelT]:
        if error.classification is ErrorClassification.TRANSIENT and self._retry_policy.can_retry(
            envelope.attempt
        ):
            retry = self._retry_policy.metadata_for(envelope.attempt, error)
            try:
                await self._update_stage(envelope, StageRunStatus.RETRYING, error)
            except Exception:
                pass
            await self._idempotency_store.release(key)
            if self._retry_publisher is None:
                await delivery.reject(requeue=True)
            else:
                retry_body = (
                    envelope.model_copy(update={"attempt": retry.next_attempt})
                    .model_dump_json(by_alias=True)
                    .encode("utf-8")
                )
                await self._retry_publisher.publish_retry(retry_body, retry)
                await delivery.ack()
            return ConsumerOutcome(ConsumerStatus.RETRY_SCHEDULED, error=error, retry=retry)

        try:
            await self._update_stage(envelope, StageRunStatus.FAILED, error)
        except Exception:
            pass
        await self._idempotency_store.release(key)
        await self._dead_letter(delivery, error)
        return ConsumerOutcome(ConsumerStatus.FAILED, error=error)

    async def _dead_letter(self, delivery: MessageDelivery, error: ProcessingErrorInfo) -> None:
        if self._retry_publisher is None:
            await delivery.reject(requeue=False)
            return
        try:
            await self._retry_publisher.publish_dead_letter(delivery.body, error)
        except Exception:
            await delivery.reject(requeue=True)
            return
        await delivery.ack()

    async def _update_stage(
        self,
        envelope: MessageEnvelope,
        status: StageRunStatus,
        error: ProcessingErrorInfo | None = None,
        progress: float | None = None,
    ) -> None:
        if self._stage_run_updater is None:
            return
        await self._stage_run_updater.update(
            StageRunUpdate(
                job_id=envelope.job_id,
                resource_id=envelope.resource_id,
                operation=envelope.operation,
                version=envelope.version,
                correlation_id=envelope.correlation_id,
                attempt=envelope.attempt,
                status=status,
                progress=progress,
                error_code=None if error is None else error.code,
                error_message=None if error is None else error.message,
            )
        )


BrokerAgnosticConsumer = ConsumerBase
