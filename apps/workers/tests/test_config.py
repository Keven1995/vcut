from _pytest.monkeypatch import MonkeyPatch
from fastapi.testclient import TestClient

import vcut_workers.app as worker_app
from vcut_workers.config import WorkerSettings


def test_settings_are_loaded_from_environment(monkeypatch: MonkeyPatch) -> None:
    monkeypatch.setenv("APP_ENV", "test")
    monkeypatch.setenv("WORKER_NAME", "worker-test")
    monkeypatch.setenv("FFMPEG_BINARY", "ffmpeg-test")

    settings = WorkerSettings.from_environment()

    assert settings.environment == "test"
    assert settings.worker_name == "worker-test"
    assert settings.ffmpeg_binary == "ffmpeg-test"


def test_live_endpoint_does_not_require_dependencies() -> None:
    response = TestClient(worker_app.app).get("/health/live")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def test_ready_endpoint_reports_dependency_failure(monkeypatch: MonkeyPatch) -> None:
    monkeypatch.setattr(
        worker_app,
        "check_dependencies",
        lambda settings: {"postgres": "UP", "rabbitmq": "DOWN", "storage": "UP"},
    )

    response = TestClient(worker_app.app).get("/health/ready")

    assert response.status_code == 503
    assert response.json()["status"] == "DOWN"
