from typing import cast

import pika
from pytest import MonkeyPatch

from vcut_workers.config import WorkerSettings
from vcut_workers.contracts.video_validation import (
    ValidateVideoCommand,
    VideoValidationResult,
)
from vcut_workers.worker.rabbitmq import RabbitMqWorker


class CapturingChannel:
    def __init__(self) -> None:
        self.prefetch_count: int | None = None
        self.declared_queues: list[str] = []
        self.consumed_queues: list[str] = []

    def confirm_delivery(self) -> None:
        return None

    def basic_qos(self, *, prefetch_count: int) -> None:
        self.prefetch_count = prefetch_count

    def queue_declare(self, *, queue: str, durable: bool, arguments: dict[str, object]) -> None:
        del durable, arguments
        self.declared_queues.append(queue)

    def basic_consume(self, *, queue: str, on_message_callback: object, auto_ack: bool) -> None:
        del on_message_callback, auto_ack
        self.consumed_queues.append(queue)


class CapturingConnection:
    def __init__(self, channel: CapturingChannel) -> None:
        self._channel = channel
        self.is_open = True

    def channel(self) -> CapturingChannel:
        return self._channel

    def process_data_events(self, *, time_limit: float) -> None:
        del time_limit
        self.is_open = False


def test_rabbit_consumer_applies_configured_prefetch_backpressure(
    monkeypatch: MonkeyPatch,
) -> None:
    channel = CapturingChannel()
    connection = CapturingConnection(channel)
    monkeypatch.setattr(
        "vcut_workers.worker.rabbitmq.pika.BlockingConnection",
        lambda _parameters: connection,
    )
    settings = WorkerSettings(worker_prefetch_count=4)
    worker = RabbitMqWorker(
        settings,
        ValidateVideoCommand,
        VideoValidationResult,
        lambda _command: VideoValidationResult.model_construct(),
        command_queue="test.cpu.commands",
        command_routing_key="test.cpu.validate",
        result_routing_key="test.results",
        result_event_type="TestCompleted",
    )

    worker._consume_once(cast(pika.ConnectionParameters, object()))

    assert channel.declared_queues == [
        "test.cpu.commands",
        "test.cpu.commands.premium",
    ]
    assert channel.consumed_queues == [
        "test.cpu.commands",
        "test.cpu.commands.premium",
    ]
    assert channel.prefetch_count == 4
