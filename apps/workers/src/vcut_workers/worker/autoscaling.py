from __future__ import annotations

import argparse
import base64
import json
import logging
import os
import subprocess
import time
from collections.abc import Sequence
from dataclasses import dataclass
from math import ceil
from pathlib import Path
from typing import Literal, Protocol
from urllib.parse import quote
from urllib.request import Request, urlopen

from vcut_workers.config import WorkerSettings
from vcut_workers.main import WorkerConsumerName, worker_consumers_for
from vcut_workers.worker.fair_scheduling import premium_lane_name

LOGGER = logging.getLogger(__name__)
WorkerPool = Literal["cpu", "ai", "vision", "render"]


@dataclass(frozen=True)
class QueueDepth:
    ready: int
    unacknowledged: int

    def __post_init__(self) -> None:
        if self.ready < 0 or self.unacknowledged < 0:
            raise ValueError("queue depths must not be negative")


@dataclass(frozen=True)
class AutoscalingPolicy:
    minimum_replicas: int = 0
    maximum_replicas: int = 8
    ready_messages_per_replica: int = 4
    idle_polls_before_scale_in: int = 4

    def __post_init__(self) -> None:
        if self.minimum_replicas < 0:
            raise ValueError("minimum_replicas must not be negative")
        if self.maximum_replicas < 1:
            raise ValueError("maximum_replicas must be positive")
        if self.minimum_replicas > self.maximum_replicas:
            raise ValueError("minimum_replicas must not exceed maximum_replicas")
        if self.ready_messages_per_replica < 1:
            raise ValueError("ready_messages_per_replica must be positive")
        if self.idle_polls_before_scale_in < 1:
            raise ValueError("idle_polls_before_scale_in must be positive")


@dataclass(frozen=True)
class ScalingSnapshot:
    pool: WorkerPool
    ready: int
    unacknowledged: int
    current_replicas: int
    desired_replicas: int


class QueueDepthReader(Protocol):
    def read(self, queue_name: str) -> QueueDepth: ...


class WorkerReplicaScaler(Protocol):
    def current_replicas(self, pool: WorkerPool) -> int: ...

    def scale(self, pool: WorkerPool, replicas: int) -> None: ...


def next_replica_count(
    *,
    current_replicas: int,
    ready: int,
    unacknowledged: int,
    consecutive_idle_polls: int,
    policy: AutoscalingPolicy,
) -> tuple[int, int]:
    if current_replicas < 0 or ready < 0 or unacknowledged < 0:
        raise ValueError("replica counts and queue depths must not be negative")
    if consecutive_idle_polls < 0:
        raise ValueError("consecutive_idle_polls must not be negative")

    if ready > 0:
        required = ceil(ready / policy.ready_messages_per_replica)
        desired = max(current_replicas, policy.minimum_replicas, required)
        return min(policy.maximum_replicas, desired), 0
    if unacknowledged > 0:
        return current_replicas, 0

    idle_polls = consecutive_idle_polls + 1
    current_floor = max(policy.minimum_replicas, current_replicas)
    if idle_polls >= policy.idle_polls_before_scale_in and current_floor > policy.minimum_replicas:
        return current_floor - 1, 0
    return min(policy.maximum_replicas, current_floor), idle_polls


class RabbitManagementQueueDepthReader:
    def __init__(
        self,
        management_url: str,
        username: str,
        password: str,
        virtual_host: str,
        *,
        timeout_seconds: float = 5,
    ) -> None:
        if not management_url.startswith(("http://", "https://")):
            raise ValueError("RabbitMQ management URL must use HTTP or HTTPS")
        if not username or not password or not virtual_host:
            raise ValueError("RabbitMQ management credentials and vhost are required")
        if timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be positive")
        self._base_url = management_url.rstrip("/")
        self._authorization = "Basic " + base64.b64encode(f"{username}:{password}".encode()).decode(
            "ascii"
        )
        self._virtual_host = virtual_host
        self._timeout_seconds = timeout_seconds

    def read(self, queue_name: str) -> QueueDepth:
        if not queue_name:
            raise ValueError("queue_name must not be empty")
        url = (
            f"{self._base_url}/queues/{quote(self._virtual_host, safe='')}/"
            f"{quote(queue_name, safe='')}?enable_queue_totals=true&disable_stats=true"
        )
        request = Request(url, headers={"Authorization": self._authorization})
        with urlopen(request, timeout=self._timeout_seconds) as response:
            payload = json.loads(response.read())
        if not isinstance(payload, dict):
            raise ValueError("RabbitMQ queue response must be a JSON object")
        return QueueDepth(
            ready=_non_negative_integer(payload, "messages_ready"),
            unacknowledged=_non_negative_integer(payload, "messages_unacknowledged"),
        )


class ComposeWorkerReplicaScaler:
    _SERVICES: dict[WorkerPool, tuple[str, str]] = {
        "cpu": ("workers-cpu", "workers-cpu"),
        "ai": ("workers-ai", "workers-ai"),
        "vision": ("workers-vision", "workers-vision"),
        "render": ("workers-render", "workers-render"),
    }

    def __init__(self, project_root: str) -> None:
        self._project_root = project_root

    def current_replicas(self, pool: WorkerPool) -> int:
        profile, service = self._service(pool)
        completed = subprocess.run(
            ["docker", "compose", "--profile", profile, "ps", "--quiet", service],
            cwd=self._project_root,
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        return sum(bool(line.strip()) for line in completed.stdout.splitlines())

    def scale(self, pool: WorkerPool, replicas: int) -> None:
        if replicas < 0:
            raise ValueError("replicas must not be negative")
        profile, service = self._service(pool)
        if replicas == 0:
            command = [
                "docker",
                "compose",
                "--profile",
                profile,
                "stop",
                "--timeout",
                "30",
                service,
            ]
        else:
            command = [
                "docker",
                "compose",
                "--profile",
                profile,
                "up",
                "--detach",
                "--no-deps",
                "--scale",
                f"{service}={replicas}",
                service,
            ]
        subprocess.run(
            command,
            cwd=self._project_root,
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )

    @classmethod
    def _service(cls, pool: WorkerPool) -> tuple[str, str]:
        try:
            return cls._SERVICES[pool]
        except KeyError as error:
            raise ValueError(f"unsupported worker pool: {pool}") from error


class QueueDepthAutoscaler:
    def __init__(
        self,
        pool: WorkerPool,
        queue_names: Sequence[str],
        reader: QueueDepthReader,
        scaler: WorkerReplicaScaler,
        policy: AutoscalingPolicy,
    ) -> None:
        if not queue_names:
            raise ValueError("at least one worker queue is required")
        self._pool = pool
        self._queue_names = tuple(dict.fromkeys(queue_names))
        self._reader = reader
        self._scaler = scaler
        self._policy = policy
        self._consecutive_idle_polls = 0

    def reconcile(self) -> ScalingSnapshot:
        depths = tuple(self._reader.read(queue) for queue in self._queue_names)
        ready = sum(depth.ready for depth in depths)
        unacknowledged = sum(depth.unacknowledged for depth in depths)
        current = self._scaler.current_replicas(self._pool)
        desired, self._consecutive_idle_polls = next_replica_count(
            current_replicas=current,
            ready=ready,
            unacknowledged=unacknowledged,
            consecutive_idle_polls=self._consecutive_idle_polls,
            policy=self._policy,
        )
        if desired != current:
            self._scaler.scale(self._pool, desired)
        return ScalingSnapshot(self._pool, ready, unacknowledged, current, desired)


def main() -> int:
    parser = argparse.ArgumentParser(description="Scale a local Vcut worker pool by queue depth.")
    parser.add_argument("--pool", choices=("cpu", "ai", "vision", "render"), required=True)
    parser.add_argument("--once", action="store_true", help="reconcile one time and exit")
    arguments = parser.parse_args()

    settings = WorkerSettings.from_environment()
    if not settings.rabbitmq_password:
        parser.error("RABBITMQ_PASSWORD is required")
    pool: WorkerPool = arguments.pool
    pool_settings = settings.model_copy(update={"worker_pool": pool})
    queues = _queues_for_pool(pool, pool_settings)
    if not queues:
        parser.error(f"pool {pool} has no enabled consumers for the current feature flags")

    policy = AutoscalingPolicy(
        minimum_replicas=int(os.getenv("WORKER_AUTOSCALER_MIN_REPLICAS", "0")),
        maximum_replicas=int(os.getenv("WORKER_AUTOSCALER_MAX_REPLICAS", "8")),
        ready_messages_per_replica=int(os.getenv("WORKER_AUTOSCALER_READY_PER_REPLICA", "4")),
        idle_polls_before_scale_in=int(os.getenv("WORKER_AUTOSCALER_IDLE_POLLS", "4")),
    )
    poll_seconds = float(os.getenv("WORKER_AUTOSCALER_POLL_SECONDS", "15"))
    if poll_seconds <= 0:
        parser.error("WORKER_AUTOSCALER_POLL_SECONDS must be positive")
    reader = RabbitManagementQueueDepthReader(
        os.getenv("RABBITMQ_MANAGEMENT_URL", "http://localhost:15672/api"),
        settings.rabbitmq_username,
        settings.rabbitmq_password,
        settings.rabbitmq_virtual_host,
    )
    root = os.getenv("VCUT_PROJECT_ROOT", str(Path(__file__).resolve().parents[5]))
    scaler = ComposeWorkerReplicaScaler(root)
    autoscaler = QueueDepthAutoscaler(pool, queues, reader, scaler, policy)

    while True:
        try:
            snapshot = autoscaler.reconcile()
        except Exception as error:
            LOGGER.error(
                "worker_autoscaler_reconcile_failed pool=%s error_type=%s",
                pool,
                type(error).__name__,
            )
            if arguments.once:
                return 1
        else:
            LOGGER.info(
                "worker_autoscaler_reconciled pool=%s queues=%s ready=%s unacknowledged=%s "
                "current_replicas=%s desired_replicas=%s",
                snapshot.pool,
                ",".join(queues),
                snapshot.ready,
                snapshot.unacknowledged,
                snapshot.current_replicas,
                snapshot.desired_replicas,
            )
        if arguments.once:
            return 0
        time.sleep(poll_seconds)


def _queues_for_pool(pool: WorkerPool, settings: WorkerSettings) -> tuple[str, ...]:
    queue_by_consumer: dict[WorkerConsumerName, str] = {
        "video-validation": settings.rabbitmq_command_queue,
        "transcription": settings.rabbitmq_transcription_queue,
        "clip-analysis": settings.rabbitmq_clip_analysis_queue,
        "clip-generation": settings.rabbitmq_clip_generation_queue,
        "final-render": settings.rabbitmq_final_render_queue,
    }
    pool_settings = settings.model_copy(update={"worker_pool": pool})
    return tuple(
        lane
        for consumer in worker_consumers_for(pool_settings)
        for lane in (
            queue_by_consumer[consumer],
            premium_lane_name(queue_by_consumer[consumer]),
        )
    )


def _non_negative_integer(payload: dict[str, object], field_name: str) -> int:
    value = payload.get(field_name)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"RabbitMQ queue response has invalid {field_name}")
    return value


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    raise SystemExit(main())
