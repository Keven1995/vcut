import logging
import os
from collections.abc import Callable
from threading import Thread
from typing import Literal

import uvicorn

from vcut_workers.app import app
from vcut_workers.config import WorkerSettings
from vcut_workers.observability import configure_logging
from vcut_workers.observability_tracing import configure_tracing
from vcut_workers.worker.rabbitmq import (
    create_clip_analysis_worker,
    create_clip_generation_worker,
    create_final_render_worker,
    create_transcription_worker,
    create_video_validation_worker,
)
from vcut_workers.worker.retention_cleanup import RetentionCleanupWorker

WorkerConsumerName = Literal[
    "video-validation", "transcription", "clip-analysis", "clip-generation", "final-render"
]
WorkerTarget = Callable[[WorkerConsumerName, WorkerSettings], None]


def worker_consumers_for(settings: WorkerSettings) -> tuple[WorkerConsumerName, ...]:
    pool = settings.worker_pool
    consumers: list[WorkerConsumerName] = []
    if pool in {"all", "cpu"}:
        consumers.append("video-validation")
    if pool in {"all", "ai"}:
        consumers.append("transcription")
    if pool == "all" or (pool == "ai" and not settings.multimodal_analysis_enabled):
        consumers.append("clip-analysis")
    if pool == "vision" and settings.multimodal_analysis_enabled:
        consumers.append("clip-analysis")
    if pool in {"all", "render"} and settings.clip_generation_enabled:
        if pool == "all" or not settings.smart_reframing_enabled:
            consumers.extend(("clip-generation", "final-render"))
    if pool == "vision" and settings.clip_generation_enabled and settings.smart_reframing_enabled:
        consumers.extend(("clip-generation", "final-render"))
    return tuple(consumers)


def worker_instances_for(settings: WorkerSettings) -> tuple[tuple[WorkerConsumerName, int], ...]:
    return tuple(
        (consumer, instance)
        for consumer in worker_consumers_for(settings)
        for instance in range(1, settings.worker_concurrency + 1)
    )


def _worker_target(consumer: WorkerConsumerName, settings: WorkerSettings) -> None:
    if consumer == "video-validation":
        create_video_validation_worker(settings).run_forever()
    elif consumer == "transcription":
        create_transcription_worker(settings).run_forever()
    elif consumer == "clip-analysis":
        create_clip_analysis_worker(settings).run_forever()
    elif consumer == "clip-generation":
        create_clip_generation_worker(settings).run_forever()
    elif consumer == "final-render":
        create_final_render_worker(settings).run_forever()


def start_worker_consumers(
    settings: WorkerSettings,
    *,
    worker_target: WorkerTarget = _worker_target,
) -> tuple[Thread, ...]:
    threads = tuple(
        Thread(
            target=worker_target,
            args=(consumer, settings),
            name=f"rabbitmq-{consumer}-{instance}",
            daemon=True,
        )
        for consumer, instance in worker_instances_for(settings)
    )
    for thread in threads:
        thread.start()
    return threads


def main() -> None:
    configure_logging()
    configure_tracing()
    settings = WorkerSettings.from_environment()
    if settings.retention_cleanup_enabled and os.getenv("WORKER_RUN_ONCE") == "true":
        RetentionCleanupWorker(settings).run_once()
        return
    if os.getenv("WORKER_CONSUME") == "true":
        consumers = worker_instances_for(settings)
        if not consumers:
            logging.getLogger(__name__).warning(
                "worker_pool_has_no_consumers",
                extra={"workerPool": settings.worker_pool},
            )
        start_worker_consumers(settings)
    if settings.retention_cleanup_enabled and settings.worker_pool in {"all", "cpu"}:
        Thread(
            target=RetentionCleanupWorker(settings).run_forever,
            name="retention-cleanup-worker",
            daemon=True,
        ).start()
    if os.getenv("WORKER_RUN_ONCE") == "true":
        logging.getLogger(__name__).info(
            "worker_started",
            extra={"workerName": settings.worker_name, "environment": settings.environment},
        )
        return

    uvicorn.run(app, host="0.0.0.0", port=settings.health_port, log_level="info")
