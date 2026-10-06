from typing import cast

import pika

from vcut_workers.worker.fair_scheduling import PlanLaneScheduler


def test_scheduler_serves_basic_after_at_most_four_premium_deliveries() -> None:
    scheduler = PlanLaneScheduler("validation", "validation.premium")
    for index in range(12):
        scheduler.enqueue(
            "validation.premium",
            cast(pika.spec.Basic.Deliver, object()),
            pika.BasicProperties(),
            f"premium-{index}".encode(),
        )
    for index in range(3):
        scheduler.enqueue(
            "validation",
            cast(pika.spec.Basic.Deliver, object()),
            pika.BasicProperties(),
            f"basic-{index}".encode(),
        )

    selected: list[str] = []
    for _ in range(5):
        delivery = scheduler.next_delivery()
        assert delivery is not None
        selected.append(delivery.queue_name)

    assert selected == ["validation.premium"] * 4 + ["validation"]


def test_empty_basic_lane_does_not_idle_premium_and_later_basic_is_served() -> None:
    scheduler = PlanLaneScheduler("analysis", "analysis.premium")
    for index in range(6):
        scheduler.enqueue(
            "analysis.premium",
            cast(pika.spec.Basic.Deliver, object()),
            pika.BasicProperties(),
            f"premium-{index}".encode(),
        )

    for _ in range(6):
        delivery = scheduler.next_delivery()
        assert delivery is not None
        assert delivery.queue_name == "analysis.premium"

    scheduler.enqueue(
        "analysis",
        cast(pika.spec.Basic.Deliver, object()),
        pika.BasicProperties(),
        b"basic",
    )

    basic_delivery = scheduler.next_delivery()
    assert basic_delivery is not None
    assert basic_delivery.queue_name == "analysis"
