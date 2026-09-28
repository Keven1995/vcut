import os

import uvicorn

from vcut_workers.app import app
from vcut_workers.config import WorkerSettings


def main() -> None:
    settings = WorkerSettings.from_environment()
    if os.getenv("WORKER_RUN_ONCE") == "true":
        print(f"{settings.worker_name} started in {settings.environment} environment")
        return

    uvicorn.run(app, host="0.0.0.0", port=settings.health_port, log_level="info")
