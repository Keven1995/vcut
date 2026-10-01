import asyncio
import logging
import time
from collections.abc import Callable
from datetime import UTC, datetime
from typing import cast
from uuid import uuid4

import pika
from pydantic import BaseModel, ValidationError

from vcut_workers.application.video_validation import ValidateUploadedVideoUseCase
from vcut_workers.config import WorkerSettings
from vcut_workers.contracts.messaging import (
    MessageEnvelope,
    MessageKind,
    RetryMetadata,
    StageRunUpdate,
)
from vcut_workers.contracts.video_validation import ValidateVideoCommand, VideoValidationResult
from vcut_workers.infrastructure.ffmpeg.processor import FFmpegVideoProcessor
from vcut_workers.infrastructure.persistence.idempotency import PostgresIdempotencyStore
from vcut_workers.infrastructure.storage.s3 import S3ObjectStorage
from vcut_workers.worker.consumer import (
    ConsumerBase,
    MessageDelivery,
    ResultPublisher,
    RetryPublisher,
)
from vcut_workers.worker.errors import ProcessingErrorInfo
from vcut_workers.worker.retry import RetryPolicy

LOGGER = logging.getLogger(__name__)


class PikaDelivery(MessageDelivery):
    def __init__(
        self, channel: pika.adapters.blocking_connection.BlockingChannel, body: bytes, tag: int
    ) -> None:
        self._channel = channel
        self._body = body
        self._tag = tag

    @property
    def body(self) -> bytes:
        return self._body

    @property
    def delivery_tag(self) -> str:
        return str(self._tag)

    async def ack(self) -> None:
        self._channel.basic_ack(delivery_tag=self._tag)

    async def reject(self, *, requeue: bool) -> None:
        self._channel.basic_nack(delivery_tag=self._tag, requeue=requeue)


class PikaPublisher(RetryPublisher, ResultPublisher):
    def __init__(
        self, channel: pika.adapters.blocking_connection.BlockingChannel, settings: WorkerSettings
    ) -> None:
        self._channel = channel
        self._settings = settings

    async def publish_retry(self, body: bytes, metadata: RetryMetadata) -> None:
        self._channel.basic_publish(
            exchange=self._settings.rabbitmq_retry_exchange,
            routing_key="pipeline.video.validate",
            body=body,
            properties=pika.BasicProperties(
                content_type="application/json",
                delivery_mode=2,
                expiration=str(max(1, int(metadata.backoff_seconds * 1000))),
            ),
        )

    async def publish_dead_letter(self, body: bytes, error: ProcessingErrorInfo) -> None:
        self._channel.basic_publish(
            exchange=self._settings.rabbitmq_dead_letter_exchange,
            routing_key="pipeline.video.validate",
            body=body,
            properties=pika.BasicProperties(
                content_type="application/json",
                delivery_mode=2,
                headers={
                    "x-error-code": error.code,
                    "x-error-message": error.message,
                    "x-error-classification": error.classification.value,
                },
            ),
        )
        try:
            envelope = MessageEnvelope.model_validate_json(body)
        except ValidationError:
            return
        failure_body = _result_envelope(
            envelope,
            {
                "status": "REJECTED",
                "failureCode": error.code,
                "actualSizeBytes": 0,
            },
        )
        self._publish_result(failure_body)

    async def publish_result(self, envelope: MessageEnvelope, result: BaseModel) -> None:
        data = cast(
            dict[str, object], result.model_dump(mode="json", by_alias=True, exclude_none=True)
        )
        self._publish_result(_result_envelope(envelope, data))

    async def update(self, update: StageRunUpdate) -> None:
        data: dict[str, object] = {
            "status": update.status.value,
            "attempt": update.attempt,
        }
        if update.progress is not None:
            data["progress"] = update.progress
        if update.error_code is not None:
            data["errorCode"] = update.error_code
        if update.error_message is not None:
            data["errorMessage"] = update.error_message
        self._publish_result(_stage_update_envelope(update, data))

    def _publish_result(self, body: bytes) -> None:
        self._channel.basic_publish(
            exchange=self._settings.rabbitmq_result_exchange,
            routing_key=self._settings.rabbitmq_result_routing_key,
            body=body,
            properties=pika.BasicProperties(content_type="application/json", delivery_mode=2),
        )


class RabbitMqWorker:
    def __init__(
        self,
        settings: WorkerSettings,
        handler: Callable[[ValidateVideoCommand], VideoValidationResult],
    ) -> None:
        self._settings = settings
        self._handler = handler

    def run_forever(self) -> None:
        credentials = pika.PlainCredentials(
            self._settings.rabbitmq_username, self._settings.rabbitmq_password
        )
        parameters = pika.ConnectionParameters(
            host=self._settings.rabbitmq_host,
            port=self._settings.rabbitmq_port,
            virtual_host=self._settings.rabbitmq_virtual_host,
            credentials=credentials,
            heartbeat=60,
        )
        while True:
            try:
                self._consume_once(parameters)
            except KeyboardInterrupt:
                raise
            except Exception as exception:
                LOGGER.warning("worker_connection_failed error=%s", exception)
                time.sleep(5)

    def _consume_once(self, parameters: pika.ConnectionParameters) -> None:
        connection = pika.BlockingConnection(parameters)
        channel = connection.channel()
        channel.confirm_delivery()
        channel.basic_qos(prefetch_count=1)
        channel.queue_declare(
            queue=self._settings.rabbitmq_command_queue,
            durable=True,
            arguments={
                "x-dead-letter-exchange": self._settings.rabbitmq_dead_letter_exchange,
                "x-dead-letter-routing-key": "pipeline.video.validate",
            },
        )
        publisher = PikaPublisher(channel, self._settings)
        consumer = ConsumerBase(
            ValidateVideoCommand,
            VideoValidationResult,
            self._handler,
            PostgresIdempotencyStore(self._settings),
            retry_policy=RetryPolicy(
                max_attempts=3, initial_backoff_seconds=5, backoff_multiplier=6
            ),
            retry_publisher=publisher,
            result_publisher=publisher,
            stage_run_updater=publisher,
        )

        def on_message(
            current_channel: pika.adapters.blocking_connection.BlockingChannel,
            method: pika.spec.Basic.Deliver,
            properties: pika.BasicProperties,
            body: bytes,
        ) -> None:
            del properties
            delivery = PikaDelivery(current_channel, body, method.delivery_tag)
            try:
                asyncio.run(consumer.consume(delivery))
            except Exception as exception:
                LOGGER.exception("worker_delivery_failed error=%s", exception)
                try:
                    current_channel.basic_nack(delivery_tag=method.delivery_tag, requeue=True)
                except pika.exceptions.AMQPError:
                    LOGGER.exception("worker_delivery_requeue_failed")

        channel.basic_consume(
            queue=self._settings.rabbitmq_command_queue,
            on_message_callback=on_message,
            auto_ack=False,
        )
        channel.start_consuming()


def create_video_validation_worker(settings: WorkerSettings) -> RabbitMqWorker:
    storage = S3ObjectStorage(settings)
    processor = FFmpegVideoProcessor(settings.ffmpeg_binary)
    use_case = ValidateUploadedVideoUseCase(storage, processor)
    return RabbitMqWorker(settings, use_case.execute)


def _result_envelope(envelope: MessageEnvelope, data: dict[str, object]) -> bytes:
    result = MessageEnvelope.model_validate(
        {
            "kind": MessageKind.EVENT,
            "eventId": uuid4(),
            "eventType": "VideoValidationCompleted",
            "eventVersion": 1,
            "jobId": envelope.job_id,
            "resourceId": envelope.resource_id,
            "operation": envelope.operation,
            "version": envelope.version,
            "correlationId": envelope.correlation_id,
            "attempt": envelope.attempt,
            "occurredAt": datetime.now(UTC),
            "data": data,
        }
    )
    return result.model_dump_json(by_alias=True).encode("utf-8")


def _stage_update_envelope(update: StageRunUpdate, data: dict[str, object]) -> bytes:
    result = MessageEnvelope.model_validate(
        {
            "kind": MessageKind.EVENT,
            "eventId": uuid4(),
            "eventType": "StageRunUpdated",
            "eventVersion": 1,
            "jobId": update.job_id,
            "resourceId": update.resource_id,
            "operation": update.operation,
            "version": update.version,
            "correlationId": update.correlation_id,
            "attempt": update.attempt,
            "occurredAt": datetime.now(UTC),
            "data": data,
        }
    )
    return result.model_dump_json(by_alias=True).encode("utf-8")
