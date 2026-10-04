import json
import logging

from fastapi.testclient import TestClient

from vcut_workers.app import app
from vcut_workers.observability import JsonLogFormatter, sanitize_log_text
from vcut_workers.worker.metrics import WORKER_MESSAGES


def test_sanitizer_masks_secret_values_and_signed_urls() -> None:
    text = "password=pass token=abc https://storage/file?X-Amz-Signature=signed"

    sanitized = sanitize_log_text(text)

    assert "=pass" not in sanitized
    assert "=abc" not in sanitized
    assert "=signed" not in sanitized
    assert "password=[REDACTED]" in sanitized


def test_formatter_emits_structured_json_without_secret_values() -> None:
    record = logging.LogRecord(
        "worker",
        logging.INFO,
        __file__,
        1,
        "job_completed token=%s",
        ("secret-token",),
        None,
    )

    payload = json.loads(JsonLogFormatter().format(record))

    assert payload["level"] == "INFO"
    assert payload["message"] == "job_completed token=[REDACTED]"


def test_metrics_endpoint_exposes_worker_delivery_counters() -> None:
    WORKER_MESSAGES.labels(queue="test.queue", outcome="SUCCEEDED").inc()

    response = TestClient(app).get("/metrics/")

    assert response.status_code == 200
    assert 'vcut_worker_messages_total{outcome="SUCCEEDED",queue="test.queue"}' in response.text
