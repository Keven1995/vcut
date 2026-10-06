import os
import threading
import time
from uuid import uuid4

import pika
import pytest

from vcut_workers.config import WorkerSettings
from vcut_workers.worker.fair_scheduling import PlanLaneScheduler, premium_lane_name

pytestmark = pytest.mark.skipif(
    os.getenv("VCUT_RUN_RABBITMQ_INTEGRATION") != "true",
    reason="set VCUT_RUN_RABBITMQ_INTEGRATION=true to use the local RabbitMQ broker",
)


def test_basic_plan_is_served_during_continuous_premium_arrivals() -> None:
    settings = WorkerSettings.from_environment()
    if not settings.rabbitmq_password:
        pytest.skip("RABBITMQ_PASSWORD is required for the broker integration test")

    connection = _connect(settings)
    channel = connection.channel()
    basic_queue = f"vcut.sprint17.fair.{uuid4().hex}"
    premium_queue = premium_lane_name(basic_queue)
    queue_arguments = {"x-max-priority": settings.rabbitmq_max_priority}
    for queue_name in (basic_queue, premium_queue):
        channel.queue_declare(
            queue=queue_name,
            durable=False,
            exclusive=False,
            auto_delete=False,
            arguments=queue_arguments,
        )

    publisher_connection = _connect(settings)
    publisher = publisher_connection.channel()
    producer_stopped = threading.Event()
    producer_started = threading.Event()
    producer_errors: list[Exception] = []
    produced_count = [0]
    produced_lock = threading.Lock()
    producer: threading.Thread | None = None

    try:
        channel.basic_publish(
            exchange="",
            routing_key=basic_queue,
            body=b"free-plan",
            properties=pika.BasicProperties(priority=0),
        )
        for index in range(64):
            publisher.basic_publish(
                exchange="",
                routing_key=premium_queue,
                body=f"initial-pro-{index}".encode("ascii"),
                properties=pika.BasicProperties(priority=5),
            )
        publisher_connection.close()

        def publish_continuously() -> None:
            producer_connection = _connect(settings)
            producer_channel = producer_connection.channel()
            try:
                while not producer_stopped.is_set():
                    with produced_lock:
                        index = produced_count[0]
                        produced_count[0] += 1
                    producer_channel.basic_publish(
                        exchange="",
                        routing_key=premium_queue,
                        body=f"pro-stream-{index}".encode("ascii"),
                        properties=pika.BasicProperties(priority=5),
                    )
                    producer_started.set()
                    time.sleep(0.001)
            except Exception as error:
                producer_errors.append(error)
            finally:
                producer_connection.close()

        producer = threading.Thread(target=publish_continuously, name="premium-publisher")
        producer.start()
        assert producer_started.wait(timeout=5)

        scheduler = PlanLaneScheduler(basic_queue, premium_queue)
        channel.basic_qos(prefetch_count=1)
        channel.basic_consume(
            queue=basic_queue,
            on_message_callback=lambda _channel, method, properties, body: scheduler.enqueue(
                basic_queue, method, properties, body
            ),
            auto_ack=False,
        )
        channel.basic_consume(
            queue=premium_queue,
            on_message_callback=lambda _channel, method, properties, body: scheduler.enqueue(
                premium_queue, method, properties, body
            ),
            auto_ack=False,
        )

        premium_deliveries_before_basic = 0
        ready_premium_when_basic_selected = 0
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            connection.process_data_events(time_limit=0.01)
            selected = scheduler.next_delivery()
            if selected is None:
                continue
            if selected.queue_name == basic_queue:
                ready_premium_when_basic_selected = channel.queue_declare(
                    queue=premium_queue, passive=True
                ).method.message_count
                channel.basic_ack(delivery_tag=selected.method.delivery_tag)
                break
            premium_deliveries_before_basic += 1
            channel.basic_ack(delivery_tag=selected.method.delivery_tag)
            time.sleep(0.005)
        else:
            pytest.fail("basic-plan delivery was not selected while premium messages continued")

        with produced_lock:
            producer_total = produced_count[0]
        assert premium_deliveries_before_basic <= 4
        assert producer_total > 0
        assert ready_premium_when_basic_selected > 0
        assert not producer_errors
    finally:
        producer_stopped.set()
        if producer is not None:
            producer.join(timeout=5)
        if publisher_connection.is_open:
            publisher_connection.close()
        channel.queue_delete(queue=basic_queue)
        channel.queue_delete(queue=premium_queue)
        connection.close()


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
