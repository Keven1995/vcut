from dataclasses import dataclass
from enum import StrEnum


class ErrorClassification(StrEnum):
    TRANSIENT = "TRANSIENT"
    PERMANENT = "PERMANENT"


@dataclass(frozen=True)
class ProcessingErrorInfo:
    classification: ErrorClassification
    code: str
    message: str


class ProcessingError(Exception):
    classification: ErrorClassification

    def __init__(self, message: str, code: str) -> None:
        super().__init__(message)
        self.code = code


class TransientProcessingError(ProcessingError):
    classification = ErrorClassification.TRANSIENT

    def __init__(self, message: str, code: str = "TRANSIENT_PROCESSING_ERROR") -> None:
        super().__init__(message, code)


class PermanentProcessingError(ProcessingError):
    classification = ErrorClassification.PERMANENT

    def __init__(self, message: str, code: str = "PERMANENT_PROCESSING_ERROR") -> None:
        super().__init__(message, code)


def classify_error(error: Exception) -> ProcessingErrorInfo:
    if isinstance(error, ProcessingError):
        return ProcessingErrorInfo(error.classification, error.code, _message(error))
    error_code = getattr(error, "code", None)
    if isinstance(error_code, str) and error_code:
        return ProcessingErrorInfo(ErrorClassification.PERMANENT, error_code, _message(error))
    if isinstance(error, TimeoutError):
        return ProcessingErrorInfo(
            ErrorClassification.TRANSIENT,
            "PROCESSING_TIMEOUT",
            "Processing exceeded its time limit.",
        )
    if isinstance(error, OSError):
        return ProcessingErrorInfo(
            ErrorClassification.TRANSIENT,
            "EXTERNAL_RESOURCE_UNAVAILABLE",
            _message(error),
        )
    if isinstance(error, ValueError | TypeError):
        return ProcessingErrorInfo(
            ErrorClassification.PERMANENT,
            "INVALID_PROCESSING_INPUT",
            _message(error),
        )
    return ProcessingErrorInfo(
        ErrorClassification.PERMANENT,
        "UNEXPECTED_PROCESSING_ERROR",
        _message(error),
    )


def _message(error: Exception) -> str:
    message = str(error).strip()
    return (message or error.__class__.__name__)[:1_000]
