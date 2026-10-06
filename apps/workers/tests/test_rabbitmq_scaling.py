import asyncio
import os
import threading
import time
from datetime import UTC, datetime
from uuid import uuid4

import pika
import pytest

from vcut_workers.config import WorkerSettings
from vcut_workers.contracts.messaging import MessageEnvelope, MessageKind
from vcut_workers.contracts.video_validation import ValidateVideoCommand, VideoValidationResult
from vcut_workers.worker.autoscaling import QueueDepth, RabbitManagementQueueDepthReader
from vcut_workers.worker.consumer import ConsumerBase, ConsumerStatus
from vcut_workers.worker.idempotency import (
    IdempotencyClaim,
    IdempotencyClaimStatus,
    IdempotencyStore,
)
from vcut_workers.worker.rabbitmq import PikaDelivery

pytestmark = pytest.mark.skipif(
    os.getenv("VCUT_RUN_RABBITMQ_INTEGRATION") != "true",
    reason="set VCUT_RUN_RABBITMQ_INTEGRATION=true to use the local RabbitMQ broker",
)


def test_basic_plan_is_served_after_a_finite_premium_priority_burst() -> None:
    settings = _settings()
    queue_name = _declare_queue(settings)
    connection = _connect(settings)
    channel = connection.channel()
    try:
        for index in range(32):
            channel.basic_publish(
                exchange="",
                routing_key=queue_name,
                body=f"pro-{index}".encode("ascii"),
                properties=pika.BasicProperties(priority=5),
            )
        channel.basic_publish(
            exchange="",
            routing_key=queue_name,
            body=b"free-plan",
            properties=pika.BasicProperties(priority=0),
        )

        delivered = _drain(channel, queue_name)

        assert len(delivered) == 33
        assert delivered[0].startswith("pro-")
        assert delivered[-1] == "free-plan"
    finally:
        channel.queue_delete(queue=queue_name)
        connection.close()


def test_management_api_reports_ready_and_unacknowledged_queue_depth() -> None:
    settings = _settings()
    queue_name = _declare_queue(settings)
    publisher_connection = _connect(settings)
    publisher = publisher_connection.channel()
    publisher.confirm_delivery()
    interrupted_connection = _connect(settings)
    interrupted_channel = interrupted_connection.channel()
    try:
        for index in range(2):
            publisher.basic_publish(
                exchange="",
                routing_key=queue_name,
                body=f"job-{index}".encode("ascii"),
                properties=pika.BasicProperties(delivery_mode=2),
            )
        delivery, _properties, _body = interrupted_channel.basic_get(
            queue=queue_name, auto_ack=False
        )
        assert delivery is not None
        reader = RabbitManagementQueueDepthReader(
            os.getenv("RABBITMQ_MANAGEMENT_URL", "http://localhost:15672/api"),
            settings.rabbitmq_username,
            settings.rabbitmq_password,
            settings.rabbitmq_virtual_host,
        )

        assert reader.read(queue_name) == QueueDepth(ready=1, unacknowledged=1)
    finally:
        interrupted_connection.close()
        publisher.queue_delete(queue=queue_name)
        publisher_connection.close()


def test_scale_out_and_consumer_disconnect_do_not_lose_messages() -> None:
    settings = _settings()
    queue_name = _declare_queue(settings)
    publisher_connection = _connect(settings)
    publisher = publisher_connection.channel()
    message_count = 64
    try:
        for index in range(message_count):
            publisher.basic_publish(
                exchange="",
                routing_key=queue_name,
                body=f"job-{index}".encode("ascii"),
                properties=pika.BasicProperties(delivery_mode=2),
            )

        interrupted_connection = _connect(settings)
        interrupted_channel = interrupted_connection.channel()
        method, _properties, body = interrupted_channel.basic_get(queue=queue_name, auto_ack=False)
        assert method is not None
        interrupted_connection.close()

        processed: list[str] = []
        processed_lock = threading.Lock()

        def consume_batch() -> None:
            connection = _connect(settings)
            channel = connection.channel()
            channel.basic_qos(prefetch_count=1)
            try:
                while True:
                    delivery, _properties, message = channel.basic_get(
                        queue=queue_name, auto_ack=False
                    )
                    if delivery is None:
                        return
                    time.sleep(0.002)
                    channel.basic_ack(delivery_tag=delivery.delivery_tag)
                    with processed_lock:
                        processed.append(message.decode("ascii"))
            finally:
                connection.close()

        consumers = [threading.Thread(target=consume_batch) for _ in range(2)]
        for consumer in consumers:
            consumer.start()
        for consumer in consumers:
            consumer.join(timeout=10)

        assert all(not consumer.is_alive() for consumer in consumers)
        assert len(processed) == message_count
        assert body.decode("ascii") in processed
        assert len(set(processed)) == message_count
    finally:
        publisher.queue_delete(queue=queue_name)
        publisher_connection.close()


def test_two_consumers_process_fake_jobs_once_after_unacked_delivery_recovery() -> None:
    settings = _settings()
    queue_name = _declare_queue(settings)
    publisher_connection = _connect(settings)
    publisher = publisher_connection.channel()
    publisher.confirm_delivery()
    job_count = 32
    job_bodies = [_fake_validation_job_body() for _ in range(job_count)]
    try:
        for body in job_bodies:
            publisher.basic_publish(
                exchange="",
                routing_key=queue_name,
                body=body,
                properties=pika.BasicProperties(delivery_mode=2),
            )

        interrupted_connection = _connect(settings)
        interrupted_channel = interrupted_connection.channel()
        delivery, _properties, _body = interrupted_channel.basic_get(
            queue=queue_name, auto_ack=False
        )
        assert delivery is not None
        interrupted_connection.close()

        handler_calls: list[str] = []
        handler_lock = threading.Lock()

        def handle(command: ValidateVideoCommand) -> VideoValidationResult:
            time.sleep(0.005)
            with handler_lock:
                handler_calls.append(str(command.video_id))
            return VideoValidationResult(
                video_id=command.video_id,
                object_key=command.object_key,
                original_filename=command.original_filename,
                status="READY",
                actual_size_bytes=command.declared_size_bytes,
            )

        consumer = ConsumerBase(
            ValidateVideoCommand,
            VideoValidationResult,
            handle,
            ThreadSafeIdempotencyStore(),
            queue_name=queue_name,
        )
        processed: list[str] = []
        processed_by_consumer: dict[str, int] = {}
        processed_lock = threading.Lock()
        start = threading.Barrier(3)

        def consume_batch(consumer_name: str) -> None:
            connection = _connect(settings)
            channel = connection.channel()
            channel.basic_qos(prefetch_count=1)
            start.wait(timeout=5)
            try:
                while True:
                    method, _properties, body = channel.basic_get(queue=queue_name, auto_ack=False)
                    if method is None:
                        return
                    outcome = asyncio.run(
                        consumer.consume(PikaDelivery(channel, body, method.delivery_tag))
                    )
                    if outcome.status is ConsumerStatus.SUCCEEDED:
                        assert outcome.result is not None
                        with processed_lock:
                            processed.append(str(outcome.result.video_id))
                            processed_by_consumer[consumer_name] = (
                                processed_by_consumer.get(consumer_name, 0) + 1
                            )
            finally:
                connection.close()

        consumers = [
            threading.Thread(target=consume_batch, args=(f"consumer-{index}",))
            for index in range(2)
        ]
        for consumer_thread in consumers:
            consumer_thread.start()
        start.wait(timeout=5)
        for consumer_thread in consumers:
            consumer_thread.join(timeout=15)

        assert all(not consumer_thread.is_alive() for consumer_thread in consumers)
        assert len(processed) == job_count
        assert len(set(processed)) == job_count
        assert len(handler_calls) == job_count
        assert len(processed_by_consumer) == 2

        duplicate_connection = _connect(settings)
        duplicate_channel = duplicate_connection.channel()
        try:
            publisher.basic_publish(
                exchange="",
                routing_key=queue_name,
                body=job_bodies[0],
                properties=pika.BasicProperties(delivery_mode=2),
            )
            method, _properties, duplicate_body = duplicate_channel.basic_get(
                queue=queue_name, auto_ack=False
            )
            assert method is not None
            duplicate_outcome = asyncio.run(
                consumer.consume(
                    PikaDelivery(duplicate_channel, duplicate_body, method.delivery_tag)
                )
            )
            assert duplicate_outcome.status is ConsumerStatus.DUPLICATE
            assert len(handler_calls) == job_count
        finally:
            duplicate_connection.close()
    finally:
        publisher.queue_delete(queue=queue_name)
        publisher_connection.close()


def _settings() -> WorkerSettings:
    settings = WorkerSettings.from_environment()
    if not settings.rabbitmq_password:
        pytest.skip("RABBITMQ_PASSWORD is required for the broker integration test")
    return settings


def _connect(settings: WorkerSettings) -> pika.BlockingConnection:
    return pika.BlockingConnection(
        pika.ConnectionParameters(
            host=settings.rabbitmq_host,
            port=settings.rabbitmq_port,
            virtual_host=settings.rabbitmq_virtual_host,
            credentials=pika.PlainCredentials(
                settings.rabbitmq_username,
                settings.rabbitmq_password,
            ),
            heartbeat=60,
        )
    )


def _declare_queue(settings: WorkerSettings) -> str:
    queue_name = f"vcut.sprint17.load.{uuid4().hex}"
    connection = _connect(settings)
    try:
        connection.channel().queue_declare(
            queue=queue_name,
            durable=False,
            exclusive=False,
            auto_delete=False,
            arguments={"x-max-priority": settings.rabbitmq_max_priority},
        )
    finally:
        connection.close()
    return queue_name


def _drain(
    channel: pika.adapters.blocking_connection.BlockingChannel, queue_name: str
) -> list[str]:
    messages: list[str] = []
    while True:
        method, _properties, body = channel.basic_get(queue=queue_name, auto_ack=False)
        if method is None:
            return messages
        channel.basic_ack(delivery_tag=method.delivery_tag)
        messages.append(body.decode("ascii"))


def _fake_validation_job_body() -> bytes:
    video_id = uuid4()
    command = ValidateVideoCommand(
        video_id=video_id,
        object_key=f"users/load/source/{video_id}.mp4",
        original_filename=f"load-{video_id}.mp4",
        declared_content_type="video/mp4",
        declared_size_bytes=2_048,
    )
    envelope = MessageEnvelope(
        kind=MessageKind.COMMAND,
        eventId=uuid4(),
        eventType="ValidateVideo",
        eventVersion=1,
        jobId=uuid4(),
        resourceId=video_id,
        operation="video.validate",
        version=1,
        correlationId=uuid4(),
        attempt=1,
        occurredAt=datetime.now(UTC),
        data=command.model_dump(mode="json", by_alias=True),
    )
    return envelope.model_dump_json(by_alias=True).encode("utf-8")


class ThreadSafeIdempotencyStore(IdempotencyStore):
    def __init__(self) -> None:
        self._records: dict[str, bytes | None] = {}
        self._lock = threading.Lock()

    async def claim(self, key: str) -> IdempotencyClaim:
        with self._lock:
            if key not in self._records:
                self._records[key] = None
                return IdempotencyClaim(IdempotencyClaimStatus.CLAIMED)
            result = self._records[key]
            if result is None:
                return IdempotencyClaim(IdempotencyClaimStatus.IN_PROGRESS)
            return IdempotencyClaim(IdempotencyClaimStatus.COMPLETED, result)

    async def complete(self, key: str, result_json: bytes) -> None:
        with self._lock:
            if key not in self._records or self._records[key] is not None:
                raise ValueError("idempotency key was not claimed")
            self._records[key] = result_json

    async def release(self, key: str) -> None:
        with self._lock:
            if self._records.get(key) is None:
                self._records.pop(key, None)
