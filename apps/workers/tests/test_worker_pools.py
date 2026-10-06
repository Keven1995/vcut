from threading import Event

from vcut_workers.config import WorkerSettings
from vcut_workers.main import (
    start_worker_consumers,
    worker_consumers_for,
    worker_instances_for,
)


def test_default_pool_preserves_all_existing_operation_queues() -> None:
    assert worker_consumers_for(WorkerSettings()) == (
        "video-validation",
        "transcription",
        "clip-analysis",
        "clip-generation",
        "final-render",
    )


def test_cpu_ai_and_render_pools_have_independent_queue_sets() -> None:
    cpu = set(worker_consumers_for(WorkerSettings(worker_pool="cpu")))
    ai = set(worker_consumers_for(WorkerSettings(worker_pool="ai")))
    render = set(worker_consumers_for(WorkerSettings(worker_pool="render")))

    assert cpu == {"video-validation"}
    assert ai == {"transcription", "clip-analysis"}
    assert render == {"clip-generation", "final-render"}
    assert cpu.isdisjoint(ai)
    assert cpu.isdisjoint(render)
    assert ai.isdisjoint(render)


def test_multimodal_clip_analysis_is_assigned_to_the_vision_pool() -> None:
    settings = WorkerSettings(worker_pool="vision", multimodal_analysis_enabled=True)

    assert worker_consumers_for(settings) == ("clip-analysis",)
    assert "clip-analysis" not in worker_consumers_for(
        WorkerSettings(worker_pool="ai", multimodal_analysis_enabled=True)
    )


def test_smart_reframing_moves_render_consumers_into_the_vision_pool() -> None:
    settings = WorkerSettings(worker_pool="vision", smart_reframing_enabled=True)

    assert worker_consumers_for(settings) == ("clip-generation", "final-render")
    assert (
        worker_consumers_for(WorkerSettings(worker_pool="render", smart_reframing_enabled=True))
        == ()
    )


def test_worker_concurrency_creates_bounded_independent_consumers() -> None:
    settings = WorkerSettings(worker_pool="cpu", worker_concurrency=3)

    assert worker_instances_for(settings) == (
        ("video-validation", 1),
        ("video-validation", 2),
        ("video-validation", 3),
    )


def test_ai_pool_runs_while_cpu_pool_consumer_is_saturated() -> None:
    cpu_started = Event()
    release_cpu = Event()
    ai_completed = Event()

    def hold_cpu_pool(_consumer: str, settings: WorkerSettings) -> None:
        if settings.worker_pool == "cpu":
            cpu_started.set()
            release_cpu.wait(timeout=5)
        else:
            ai_completed.set()

    cpu_threads = start_worker_consumers(
        WorkerSettings(worker_pool="cpu"), worker_target=hold_cpu_pool
    )
    assert cpu_started.wait(timeout=1)
    ai_threads = start_worker_consumers(
        WorkerSettings(worker_pool="ai"), worker_target=hold_cpu_pool
    )

    try:
        assert ai_completed.wait(timeout=1)
        assert cpu_threads[0].is_alive()
        assert not release_cpu.is_set()
    finally:
        release_cpu.set()
        for thread in (*cpu_threads, *ai_threads):
            thread.join(timeout=1)
