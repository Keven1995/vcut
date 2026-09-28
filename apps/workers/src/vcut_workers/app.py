from fastapi import FastAPI
from fastapi.responses import JSONResponse

from vcut_workers.config import WorkerSettings
from vcut_workers.dependencies import check_dependencies

settings = WorkerSettings.from_environment()
app = FastAPI(title="Vcut workers", version="0.1.0")


@app.get("/health/live")
def live() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/health/ready")
def ready() -> JSONResponse:
    dependencies = check_dependencies(settings)
    available = all(status == "UP" for status in dependencies.values())
    body: dict[str, object] = {
        "status": "UP" if available else "DOWN",
        "dependencies": dependencies,
    }
    return JSONResponse(status_code=200 if available else 503, content=body)
