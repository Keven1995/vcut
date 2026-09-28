import json
import logging
import re
import sys
from typing import Final

_SECRET_PARAMETER: Final[re.Pattern[str]] = re.compile(
    r"(?i)(authorization|password|token|secret|api[-_]key|signature)(\s*[:=]\s*)([^\s,&]+)"
)


def sanitize_log_text(value: str) -> str:
    return _SECRET_PARAMETER.sub(r"\1\2[REDACTED]", value)


class JsonLogFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, object] = {
            "timestamp": self.formatTime(record, "%Y-%m-%dT%H:%M:%S%z"),
            "level": record.levelname,
            "logger": record.name,
            "message": sanitize_log_text(record.getMessage()),
        }
        correlation_id = getattr(record, "correlation_id", None)
        if correlation_id is not None:
            payload["correlationId"] = sanitize_log_text(str(correlation_id))
        return json.dumps(payload, default=str, separators=(",", ":"))


def configure_logging() -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonLogFormatter())
    root_logger = logging.getLogger()
    root_logger.handlers.clear()
    root_logger.addHandler(handler)
    root_logger.setLevel(logging.INFO)
