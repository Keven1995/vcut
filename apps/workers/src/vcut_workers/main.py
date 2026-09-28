import logging
import os

import uvicorn

from vcut_workers.app import app
from vcut_workers.config import WorkerSettings
from vcut_workers.observability import configure_logging


def main() -> None:
    configure_logging()
    settings = WorkerSettings.from_environment()
    if os.getenv("WORKER_RUN_ONCE") == "true":
        logging.getLogger(__name__).info(
            "worker_started",
            extra={"workerName": settings.worker_name, "environment": settings.environment},
        )
        return

    uvicorn.run(app, host="0.0.0.0", port=settings.health_port, log_level="info")
