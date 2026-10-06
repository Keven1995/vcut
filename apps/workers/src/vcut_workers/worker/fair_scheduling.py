from collections import deque
from dataclasses import dataclass

import pika

PREMIUM_LANE_SUFFIX = ".premium"


def premium_lane_name(basic_lane_name: str) -> str:
    if not basic_lane_name:
        raise ValueError("basic_lane_name must not be empty")
    return f"{basic_lane_name}{PREMIUM_LANE_SUFFIX}"


@dataclass(frozen=True)
class PendingDelivery:
    queue_name: str
    method: pika.spec.Basic.Deliver
    properties: pika.BasicProperties
    body: bytes


class PlanLaneScheduler:
    """Selects a basic delivery at least once per premium-weight window."""

    def __init__(
        self,
        basic_queue: str,
        premium_queue: str,
        *,
        max_consecutive_premium: int = 4,
    ) -> None:
        if not basic_queue or not premium_queue or basic_queue == premium_queue:
            raise ValueError("basic and premium queues must be distinct non-empty names")
        if max_consecutive_premium < 1:
            raise ValueError("max_consecutive_premium must be positive")
        self.basic_queue = basic_queue
        self.premium_queue = premium_queue
        self._max_consecutive_premium = max_consecutive_premium
        self._consecutive_premium = 0
        self._pending: dict[str, deque[PendingDelivery]] = {
            basic_queue: deque(),
            premium_queue: deque(),
        }

    def enqueue(
        self,
        queue_name: str,
        method: pika.spec.Basic.Deliver,
        properties: pika.BasicProperties,
        body: bytes,
    ) -> None:
        try:
            pending = self._pending[queue_name]
        except KeyError as error:
            raise ValueError(f"queue is not a plan lane: {queue_name}") from error
        pending.append(PendingDelivery(queue_name, method, properties, body))

    def next_delivery(self) -> PendingDelivery | None:
        basic_ready = self._pending[self.basic_queue]
        premium_ready = self._pending[self.premium_queue]

        if basic_ready and (
            not premium_ready or self._consecutive_premium >= self._max_consecutive_premium
        ):
            self._consecutive_premium = 0
            return basic_ready.popleft()
        if premium_ready:
            self._consecutive_premium += 1
            return premium_ready.popleft()
        if basic_ready:
            self._consecutive_premium = 0
            return basic_ready.popleft()
        return None
