import logging
import os
from threading import Thread

import uvicorn

from vcut_workers.app import app
from vcut_workers.config import WorkerSettings
from vcut_workers.observability import configure_logging
from vcut_workers.worker.rabbitmq import (
    create_transcription_worker,
    create_video_validation_worker,
)


def main() -> None:
    configure_logging()
    settings = WorkerSettings.from_environment()
    if os.getenv("WORKER_CONSUME") == "true":
        Thread(
            target=create_video_validation_worker(settings).run_forever,
            name="rabbitmq-consumer",
            daemon=True,
        ).start()
        Thread(
            target=create_transcription_worker(settings).run_forever,
            name="rabbitmq-transcription-consumer",
            daemon=True,
        ).start()
    if os.getenv("WORKER_RUN_ONCE") == "true":
        logging.getLogger(__name__).info(
            "worker_started",
            extra={"workerName": settings.worker_name, "environment": settings.environment},
        )
        return

    uvicorn.run(app, host="0.0.0.0", port=settings.health_port, log_level="info")
