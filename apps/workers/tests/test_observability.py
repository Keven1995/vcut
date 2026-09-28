import json
import logging

from vcut_workers.observability import JsonLogFormatter, sanitize_log_text


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
