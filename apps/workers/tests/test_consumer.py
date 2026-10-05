import asyncio
from collections.abc import Callable
from datetime import UTC, datetime
from typing import cast
from uuid import UUID

import pika
import pytest
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from opentelemetry.trace import Tracer
from pydantic import BaseModel, ValidationError

from vcut_workers.config import WorkerSettings
from vcut_workers.contracts import (
    MessageEnvelope,
    MessageKind,
    RetryMetadata,
    StageRunStatus,
    StageRunUpdate,
    ValidateVideoCommand,
    VideoValidationResult,
)
from vcut_workers.worker.consumer import (
    CommandEligibilityStore,
    ConsumerBase,
    ConsumerStatus,
    ResultPublisher,
)
from vcut_workers.worker.errors import (
    ErrorClassification,
    PermanentProcessingError,
    ProcessingErrorInfo,
    TransientProcessingError,
    classify_error,
)
from vcut_workers.worker.idempotency import InMemoryIdempotencyStore
from vcut_workers.worker.rabbitmq import PikaPublisher
from vcut_workers.worker.retry import RetryPolicy

VIDEO_ID = UUID("11111111-1111-4111-8111-111111111111")
JOB_ID = UUID("22222222-2222-4222-8222-222222222222")
CORRELATION_ID = UUID("44444444-4444-4444-8444-444444444444")


class FakeDelivery:
    def __init__(self, body: bytes, tag: str = "delivery-1") -> None:
        self.body = body
        self.delivery_tag = tag
        self.actions: list[str] = []

    async def ack(self) -> None:
        self.actions.append("ack")

    async def reject(self, *, requeue: bool) -> None:
        self.actions.append("reject:requeue" if requeue else "reject:dead-letter")


class FakePublisher:
    def __init__(self) -> None:
        self.retries: list[tuple[bytes, RetryMetadata]] = []
        self.dead_letters: list[tuple[bytes, ProcessingErrorInfo]] = []

    async def publish_retry(self, body: bytes, metadata: RetryMetadata) -> None:
        self.retries.append((body, metadata))

    async def publish_dead_letter(self, body: bytes, error: ProcessingErrorInfo) -> None:
        self.dead_letters.append((body, error))


class FakeResultPublisher:
    def __init__(self, events: list[str] | None = None) -> None:
        self.results: list[VideoValidationResult] = []
        self.events = events

    async def publish_result(self, envelope: MessageEnvelope, result: BaseModel) -> None:
        self.results.append(VideoValidationResult.model_validate(result))
        if self.events is not None:
            self.events.append("result")


class FakeStageRunUpdater:
    def __init__(self, events: list[str] | None = None) -> None:
        self.updates: list[StageRunUpdate] = []
        self.events = events

    async def update(self, update: StageRunUpdate) -> None:
        self.updates.append(update)
        if self.events is not None:
            self.events.append(update.status.value)


class FakeCommandEligibilityStore:
    def __init__(self, eligible: bool) -> None:
        self.eligible = eligible

    def can_process(self, envelope: MessageEnvelope) -> bool:
        assert envelope.job_id == JOB_ID
        return self.eligible


class FakeChannel:
    def __init__(self) -> None:
        self.published: list[dict[str, object]] = []

    def basic_publish(self, **kwargs: object) -> None:
        self.published.append(kwargs)


def command() -> ValidateVideoCommand:
    return ValidateVideoCommand(
        video_id=VIDEO_ID,
        object_key="users/user/source/video.mp4",
        original_filename="video.mp4",
        declared_content_type="video/mp4",
        declared_size_bytes=5,
    )


def result() -> VideoValidationResult:
    return VideoValidationResult(
        video_id=VIDEO_ID,
        object_key="users/user/source/video.mp4",
        original_filename="video.mp4",
        status="READY",
        actual_size_bytes=5,
        duration_seconds=12.5,
        width=1920,
        height=1080,
        frame_rate=29.97,
        has_audio=True,
        video_codec="h264",
        audio_codec="aac",
    )


def body(
    attempt: int = 1,
    worker_priority: int = 0,
    traceparent: str | None = None,
) -> bytes:
    command_data = cast(dict[str, object], command().model_dump(mode="json", by_alias=True))
    command_data["workerPriority"] = worker_priority
    envelope = MessageEnvelope(
        kind=MessageKind.COMMAND,
        eventId=UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
        eventType="ValidateVideo",
        eventVersion=1,
        jobId=JOB_ID,
        resourceId=VIDEO_ID,
        operation="video.validate",
        version=1,
        correlationId=CORRELATION_ID,
        traceparent=traceparent,
        attempt=attempt,
        occurredAt=datetime(2026, 9, 29, tzinfo=UTC),
        data=command_data,
    )
    return envelope.model_dump_json(by_alias=True).encode("utf-8")


def consumer(
    handler: Callable[[ValidateVideoCommand], VideoValidationResult],
    *,
    publisher: FakePublisher | None = None,
    updater: FakeStageRunUpdater | None = None,
    store: InMemoryIdempotencyStore | None = None,
    result_publisher: ResultPublisher | None = None,
    timeout_seconds: float = 1,
    retry_policy: RetryPolicy | None = None,
    eligibility_store: CommandEligibilityStore | None = None,
    queue_name: str | None = None,
    tracer: Tracer | None = None,
) -> ConsumerBase[ValidateVideoCommand, VideoValidationResult]:
    return ConsumerBase(
        ValidateVideoCommand,
        VideoValidationResult,
        handler,
        store or InMemoryIdempotencyStore(),
        retry_policy=retry_policy,
        timeout_seconds=timeout_seconds,
        retry_publisher=publisher,
        result_publisher=result_publisher,
        stage_run_updater=updater,
        eligibility_store=eligibility_store,
        queue_name=queue_name,
        tracer=tracer,
    )


def test_validation_contracts_reject_untyped_fields() -> None:
    payload = command().model_dump()
    payload["unexpected"] = "not part of the command"

    with pytest.raises(ValidationError):
        ValidateVideoCommand.model_validate(payload)


def test_invalid_message_is_dead_lettered_and_acknowledged() -> None:
    publisher = FakePublisher()
    delivery = FakeDelivery(b'{"version": 2}')
    worker = consumer(lambda command: result(), publisher=publisher)

    outcome = asyncio.run(worker.consume(delivery))

    assert outcome.status is ConsumerStatus.FAILED
    assert delivery.actions == ["ack"]
    assert len(publisher.dead_letters) == 1
    assert publisher.dead_letters[0][1].code == "INVALID_COMMAND"


def test_pika_dead_letter_does_not_parse_an_invalid_envelope_again() -> None:
    channel = FakeChannel()
    publisher = PikaPublisher(channel, WorkerSettings(rabbitmq_password="test"))
    error = ProcessingErrorInfo(
        ErrorClassification.PERMANENT, "INVALID_COMMAND", "Unsupported version"
    )

    asyncio.run(publisher.publish_dead_letter(b'{"version": 2}', error))

    assert len(channel.published) == 1


def test_pika_retry_preserves_the_subscription_worker_priority() -> None:
    channel = FakeChannel()
    publisher = PikaPublisher(channel, WorkerSettings(rabbitmq_password="test"))
    metadata = RetryMetadata(
        attempt=1,
        max_attempts=3,
        next_attempt=2,
        backoff_seconds=5,
        error_code="TEMPORARY",
        error_message="try again",
    )

    asyncio.run(publisher.publish_retry(body(worker_priority=7), metadata))

    properties = cast(pika.BasicProperties, channel.published[0]["properties"])
    assert properties.priority == 7


def test_pika_publishes_stage_updates_as_versioned_events() -> None:
    channel = FakeChannel()
    publisher = PikaPublisher(channel, WorkerSettings(rabbitmq_password="test"))
    update = StageRunUpdate(
        job_id=JOB_ID,
        resource_id=VIDEO_ID,
        operation="video.validate",
        version=1,
        correlation_id=CORRELATION_ID,
        attempt=1,
        status=StageRunStatus.PROCESSING,
        progress=25,
    )

    asyncio.run(publisher.update(update))

    body = channel.published[0]["body"]
    assert isinstance(body, bytes)
    envelope = MessageEnvelope.model_validate_json(body)
    assert envelope.event_type == "StageRunUpdated"
    assert envelope.data == {"status": "PROCESSING", "attempt": 1, "progress": 25}


async def _test_consumer_acknowledges_only_after_typed_result_and_stage_update() -> None:
    events: list[str] = []
    updater = FakeStageRunUpdater(events)
    result_publisher = FakeResultPublisher(events)
    delivery = FakeDelivery(body())

    worker = consumer(lambda command: result(), updater=updater, result_publisher=result_publisher)
    outcome = await worker.consume(delivery)

    assert outcome.status is ConsumerStatus.SUCCEEDED
    assert outcome.result == result()
    assert result_publisher.results == [result()]
    assert delivery.actions == ["ack"]
    assert [update.status for update in updater.updates] == [
        StageRunStatus.PROCESSING,
        StageRunStatus.COMPLETED,
    ]
    assert events == ["PROCESSING", "COMPLETED", "result"]


def test_consumer_acknowledges_only_after_typed_result_and_stage_update() -> None:
    asyncio.run(_test_consumer_acknowledges_only_after_typed_result_and_stage_update())


def test_progress_handler_publishes_monotonic_stage_progress() -> None:
    updater = FakeStageRunUpdater()

    def progressive_handler(
        command: ValidateVideoCommand, report: Callable[[float], None]
    ) -> VideoValidationResult:
        del command
        report(25)
        report(10)
        report(75)
        return result()

    worker = ConsumerBase(
        ValidateVideoCommand,
        VideoValidationResult,
        lambda command: result(),
        InMemoryIdempotencyStore(),
        stage_run_updater=updater,
        progress_handler=progressive_handler,
    )

    outcome = asyncio.run(worker.consume(FakeDelivery(body())))

    assert outcome.status is ConsumerStatus.SUCCEEDED
    assert [update.progress for update in updater.updates] == [0, 25, 75, 100]


async def _test_duplicate_delivery_is_acknowledged_without_reexecuting() -> None:
    calls = 0

    def handler(command: ValidateVideoCommand) -> VideoValidationResult:
        nonlocal calls
        calls += 1
        return result()

    store = InMemoryIdempotencyStore()
    worker = consumer(handler, store=store)
    first = await worker.consume(FakeDelivery(body(), "first"))
    second_delivery = FakeDelivery(body(), "second")
    second = await worker.consume(second_delivery)

    assert first.status is ConsumerStatus.SUCCEEDED
    assert second.status is ConsumerStatus.DUPLICATE
    assert second.result == result()
    assert calls == 1
    assert second_delivery.actions == ["ack"]


def test_duplicate_delivery_is_acknowledged_without_reexecuting() -> None:
    asyncio.run(_test_duplicate_delivery_is_acknowledged_without_reexecuting())


async def _test_concurrent_delivery_requeues_while_first_claim_is_in_progress() -> None:
    started = asyncio.Event()
    release = asyncio.Event()

    async def handler(command: ValidateVideoCommand) -> VideoValidationResult:
        started.set()
        await release.wait()
        return result()

    store = InMemoryIdempotencyStore()
    worker = ConsumerBase(
        ValidateVideoCommand,
        VideoValidationResult,
        handler,
        store,
        timeout_seconds=1,
    )
    first_delivery = FakeDelivery(body(), "first")
    second_delivery = FakeDelivery(body(), "second")
    first_task = asyncio.create_task(worker.consume(first_delivery))
    await started.wait()
    second = await worker.consume(second_delivery)
    release.set()
    first = await first_task

    assert second.status is ConsumerStatus.IN_PROGRESS
    assert second_delivery.actions == ["reject:requeue"]
    assert first.status is ConsumerStatus.SUCCEEDED


def test_concurrent_delivery_requeues_while_first_claim_is_in_progress() -> None:
    asyncio.run(_test_concurrent_delivery_requeues_while_first_claim_is_in_progress())


async def _test_transient_failure_publishes_retry_before_ack() -> None:
    publisher = FakePublisher()
    delivery = FakeDelivery(body())

    def handler(command: ValidateVideoCommand) -> VideoValidationResult:
        raise TransientProcessingError("storage temporarily unavailable", "STORAGE_BUSY")

    worker = consumer(
        handler,
        publisher=publisher,
        retry_policy=RetryPolicy(
            max_attempts=3,
            initial_backoff_seconds=2,
            backoff_multiplier=2,
            max_backoff_seconds=10,
        ),
    )
    outcome = await worker.consume(delivery)

    assert outcome.status is ConsumerStatus.RETRY_SCHEDULED
    assert outcome.retry is not None
    assert outcome.retry.next_attempt == 2
    assert outcome.retry.backoff_seconds == 2
    assert outcome.retry.error_code == "STORAGE_BUSY"
    assert len(publisher.retries) == 1
    assert MessageEnvelope.model_validate_json(publisher.retries[0][0]).attempt == 2
    assert delivery.actions == ["ack"]


def test_cancelled_job_is_acknowledged_without_running_its_media_command() -> None:
    executions: list[ValidateVideoCommand] = []

    def handle(value: ValidateVideoCommand) -> VideoValidationResult:
        executions.append(value)
        return result()

    worker = consumer(
        handle,
        eligibility_store=FakeCommandEligibilityStore(False),
    )
    delivery = FakeDelivery(body())

    outcome = asyncio.run(worker.consume(delivery))

    assert outcome.status is ConsumerStatus.CANCELLED
    assert delivery.actions == ["ack"]
    assert executions == []


def test_worker_span_continues_the_correlation_trace_without_sensitive_attributes() -> None:
    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    worker = consumer(
        lambda _: result(),
        queue_name="vcut.pipeline.commands.video-validation",
        tracer=provider.get_tracer("test-worker"),
    )
    traceparent = f"00-{CORRELATION_ID.hex}-1111111111111111-01"

    outcome = asyncio.run(worker.consume(FakeDelivery(body(traceparent=traceparent))))

    spans = exporter.get_finished_spans()
    assert outcome.status is ConsumerStatus.SUCCEEDED
    assert len(spans) == 1
    span = spans[0]
    assert span.context.trace_id == int(CORRELATION_ID.hex, 16)
    assert span.parent is not None
    assert span.parent.span_id == int("1111111111111111", 16)
    assert span.attributes is not None
    assert span.attributes["messaging.destination.name"] == (
        "vcut.pipeline.commands.video-validation"
    )
    assert "users/user/source/video.mp4" not in span.attributes.values()


def test_published_result_inherits_worker_traceparent() -> None:
    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    channel = FakeChannel()
    publisher = PikaPublisher(
        channel,
        WorkerSettings(),
        command_routing_key="video.validate",
        result_routing_key="results",
        result_event_type="VideoValidationCompleted",
    )
    worker = consumer(
        lambda _: result(),
        result_publisher=publisher,
        tracer=provider.get_tracer("test-worker"),
    )
    traceparent = f"00-{CORRELATION_ID.hex}-1111111111111111-01"

    outcome = asyncio.run(worker.consume(FakeDelivery(body(traceparent=traceparent))))

    assert outcome.status is ConsumerStatus.SUCCEEDED
    event = MessageEnvelope.model_validate_json(cast(bytes, channel.published[0]["body"]))
    assert event.traceparent is not None
    assert event.traceparent.split("-")[1] == CORRELATION_ID.hex
    assert exporter.get_finished_spans()[0].context.trace_id == int(CORRELATION_ID.hex, 16)


def test_transient_failure_publishes_retry_before_ack() -> None:
    asyncio.run(_test_transient_failure_publishes_retry_before_ack())


async def _test_timeout_is_transient_and_retries_without_live_broker() -> None:
    publisher = FakePublisher()

    async def handler(command: ValidateVideoCommand) -> VideoValidationResult:
        await asyncio.sleep(0.05)
        return result()

    worker = ConsumerBase(
        ValidateVideoCommand,
        VideoValidationResult,
        handler,
        InMemoryIdempotencyStore(),
        timeout_seconds=0.001,
        retry_policy=RetryPolicy(max_attempts=2),
        retry_publisher=publisher,
    )
    outcome = await worker.consume(FakeDelivery(body()))

    assert outcome.status is ConsumerStatus.RETRY_SCHEDULED
    assert outcome.error is not None
    assert outcome.error.code == "PROCESSING_TIMEOUT"
    assert outcome.error.classification is ErrorClassification.TRANSIENT


def test_timeout_is_transient_and_retries_without_live_broker() -> None:
    asyncio.run(_test_timeout_is_transient_and_retries_without_live_broker())


async def _test_retry_limit_sends_transient_failure_to_dead_letter() -> None:
    publisher = FakePublisher()

    def handler(command: ValidateVideoCommand) -> VideoValidationResult:
        raise TransientProcessingError("provider still unavailable", "PROVIDER_BUSY")

    worker = consumer(
        handler,
        publisher=publisher,
        retry_policy=RetryPolicy(max_attempts=2),
    )
    delivery = FakeDelivery(body(attempt=2))
    outcome = await worker.consume(delivery)

    assert outcome.status is ConsumerStatus.FAILED
    assert publisher.retries == []
    assert len(publisher.dead_letters) == 1
    assert publisher.dead_letters[0][1].code == "PROVIDER_BUSY"
    assert delivery.actions == ["ack"]


def test_retry_limit_sends_transient_failure_to_dead_letter() -> None:
    asyncio.run(_test_retry_limit_sends_transient_failure_to_dead_letter())


async def _test_permanent_failure_is_dead_lettered_and_stage_is_failed() -> None:
    publisher = FakePublisher()
    updater = FakeStageRunUpdater()

    def handler(command: ValidateVideoCommand) -> VideoValidationResult:
        raise PermanentProcessingError("unsupported media", "INVALID_MEDIA")

    worker = consumer(handler, publisher=publisher, updater=updater)
    delivery = FakeDelivery(body())
    outcome = await worker.consume(delivery)

    assert outcome.status is ConsumerStatus.FAILED
    assert outcome.error is not None
    assert outcome.error.code == "INVALID_MEDIA"
    assert len(publisher.dead_letters) == 1
    assert delivery.actions == ["ack"]
    assert updater.updates[-1].status is StageRunStatus.FAILED


def test_permanent_failure_is_dead_lettered_and_stage_is_failed() -> None:
    asyncio.run(_test_permanent_failure_is_dead_lettered_and_stage_is_failed())


def test_error_classification_has_safe_defaults() -> None:
    assert classify_error(TimeoutError()).classification is ErrorClassification.TRANSIENT
    assert classify_error(ValueError("bad command")).classification is ErrorClassification.PERMANENT
