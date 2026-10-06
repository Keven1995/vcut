import subprocess

import pytest

from vcut_workers.config import WorkerSettings
from vcut_workers.worker.autoscaling import (
    AutoscalingPolicy,
    ComposeWorkerReplicaScaler,
    QueueDepth,
    QueueDepthAutoscaler,
    ScalingSnapshot,
    WorkerPool,
    _queues_for_pool,
)


class FakeQueueDepthReader:
    def __init__(self, depths: dict[str, QueueDepth]) -> None:
        self.depths = depths

    def read(self, queue_name: str) -> QueueDepth:
        return self.depths[queue_name]


class FakeWorkerReplicaScaler:
    def __init__(self, replicas: int) -> None:
        self.replicas = replicas
        self.scale_calls: list[int] = []

    def current_replicas(self, pool: WorkerPool) -> int:
        del pool
        return self.replicas

    def scale(self, pool: WorkerPool, replicas: int) -> None:
        del pool
        self.replicas = replicas
        self.scale_calls.append(replicas)


def test_scales_out_from_combined_queue_backlog() -> None:
    reader = FakeQueueDepthReader(
        {
            "transcriptions": QueueDepth(ready=5, unacknowledged=0),
            "analysis": QueueDepth(ready=4, unacknowledged=0),
        }
    )
    scaler = FakeWorkerReplicaScaler(replicas=1)
    autoscaler = QueueDepthAutoscaler(
        "ai",
        ("transcriptions", "analysis", "analysis"),
        reader,
        scaler,
        AutoscalingPolicy(ready_messages_per_replica=4),
    )

    snapshot = autoscaler.reconcile()

    assert snapshot == ScalingSnapshot("ai", 9, 0, 1, 3)
    assert scaler.scale_calls == [3]


def test_scales_in_only_after_idle_polls_and_no_unacknowledged_messages() -> None:
    reader = FakeQueueDepthReader({"validation": QueueDepth(ready=0, unacknowledged=1)})
    scaler = FakeWorkerReplicaScaler(replicas=3)
    autoscaler = QueueDepthAutoscaler(
        "cpu", ("validation",), reader, scaler, AutoscalingPolicy(idle_polls_before_scale_in=2)
    )

    in_flight = autoscaler.reconcile()
    assert in_flight.desired_replicas == 3
    assert scaler.scale_calls == []

    reader.depths["validation"] = QueueDepth(ready=0, unacknowledged=0)
    first_idle = autoscaler.reconcile()
    second_idle = autoscaler.reconcile()

    assert first_idle.desired_replicas == 3
    assert second_idle.desired_replicas == 2
    assert scaler.scale_calls == [2]


def test_queue_selection_matches_enabled_worker_consumers() -> None:
    settings = WorkerSettings()

    assert _queues_for_pool("cpu", settings) == (
        settings.rabbitmq_command_queue,
        f"{settings.rabbitmq_command_queue}.premium",
    )
    assert _queues_for_pool("ai", settings) == (
        settings.rabbitmq_transcription_queue,
        f"{settings.rabbitmq_transcription_queue}.premium",
        settings.rabbitmq_clip_analysis_queue,
        f"{settings.rabbitmq_clip_analysis_queue}.premium",
    )
    assert _queues_for_pool("vision", settings) == ()
    assert _queues_for_pool(
        "vision", settings.model_copy(update={"smart_reframing_enabled": True})
    ) == (
        settings.rabbitmq_clip_generation_queue,
        f"{settings.rabbitmq_clip_generation_queue}.premium",
        settings.rabbitmq_final_render_queue,
        f"{settings.rabbitmq_final_render_queue}.premium",
    )


def test_autoscaling_policy_does_not_scale_beyond_configured_limits() -> None:
    reader = FakeQueueDepthReader({"render": QueueDepth(ready=100, unacknowledged=0)})
    scaler = FakeWorkerReplicaScaler(replicas=1)
    autoscaler = QueueDepthAutoscaler(
        "render",
        ("render",),
        reader,
        scaler,
        AutoscalingPolicy(maximum_replicas=3, ready_messages_per_replica=2),
    )

    snapshot = autoscaler.reconcile()

    assert snapshot.desired_replicas == 3
    assert scaler.scale_calls == [3]


def test_compose_scaler_uses_graceful_stop_and_profile_scale_commands(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    commands: list[list[str]] = []

    def fake_run(command: list[str], **_kwargs: object) -> subprocess.CompletedProcess[str]:
        commands.append(command)
        return subprocess.CompletedProcess(command, 0, "container-1\ncontainer-2\n", "")

    monkeypatch.setattr("vcut_workers.worker.autoscaling.subprocess.run", fake_run)
    scaler = ComposeWorkerReplicaScaler("C:/vcut")

    assert scaler.current_replicas("cpu") == 2
    scaler.scale("cpu", 0)
    scaler.scale("render", 2)

    assert commands[1] == [
        "docker",
        "compose",
        "--profile",
        "workers-cpu",
        "stop",
        "--timeout",
        "30",
        "workers-cpu",
    ]
    assert commands[2] == [
        "docker",
        "compose",
        "--profile",
        "workers-render",
        "up",
        "--detach",
        "--no-deps",
        "--scale",
        "workers-render=2",
        "workers-render",
    ]
