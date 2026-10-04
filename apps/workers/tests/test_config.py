from _pytest.monkeypatch import MonkeyPatch
from fastapi.testclient import TestClient

import vcut_workers.app as worker_app
from vcut_workers.config import WorkerSettings


def test_settings_are_loaded_from_environment(monkeypatch: MonkeyPatch) -> None:
    monkeypatch.setenv("APP_ENV", "test")
    monkeypatch.setenv("WORKER_NAME", "worker-test")
    monkeypatch.setenv("FFMPEG_BINARY", "ffmpeg-test")
    monkeypatch.setenv("FFMPEG_TIMEOUT_SECONDS", "120")
    monkeypatch.setenv("FFMPEG_MAX_TEMP_BYTES", "1000000")
    monkeypatch.setenv("FFMPEG_MAX_MEMORY_BYTES", "2000000")
    monkeypatch.setenv("POSTGRES_DB", "vcut-test")
    monkeypatch.setenv("POSTGRES_USER", "worker-test")
    monkeypatch.setenv("POSTGRES_PASSWORD", "worker-secret")

    settings = WorkerSettings.from_environment()

    assert settings.environment == "test"
    assert settings.worker_name == "worker-test"
    assert settings.ffmpeg_binary == "ffmpeg-test"
    assert settings.ffmpeg_timeout_seconds == 120
    assert settings.ffmpeg_max_temp_bytes == 1_000_000
    assert settings.ffmpeg_max_memory_bytes == 2_000_000
    assert settings.postgres_database == "vcut-test"
    assert settings.postgres_username == "worker-test"
    assert settings.postgres_password == "worker-secret"


def test_clip_generation_defaults_match_api_messaging_contract() -> None:
    settings = WorkerSettings()

    assert settings.rabbitmq_clip_generation_queue == "vcut.pipeline.commands.clip-generation"
    assert settings.rabbitmq_clip_generation_result_routing_key == (
        "pipeline.video.clip-generation.completed"
    )
    assert (settings.clip_vertical_width, settings.clip_vertical_height) == (1080, 1920)
    assert (settings.clip_horizontal_width, settings.clip_horizontal_height) == (1920, 1080)


def test_experimental_vision_and_multimodal_flags_are_disabled_by_default() -> None:
    settings = WorkerSettings()

    assert settings.smart_reframing_enabled is False
    assert settings.face_tracking_enabled is False
    assert settings.multimodal_analysis_enabled is False
    assert settings.auto_zoom_enabled is False


def test_experimental_flags_can_be_enabled_per_worker_environment(
    monkeypatch: MonkeyPatch,
) -> None:
    monkeypatch.setenv("AI_SMART_CROP", "true")
    monkeypatch.setenv("FACE_TRACKING", "true")
    monkeypatch.setenv("MULTIMODAL_ANALYSIS", "true")
    monkeypatch.setenv("AUTO_ZOOM", "true")

    settings = WorkerSettings.from_environment()

    assert settings.smart_reframing_enabled is True
    assert settings.face_tracking_enabled is True
    assert settings.multimodal_analysis_enabled is True
    assert settings.auto_zoom_enabled is True


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
