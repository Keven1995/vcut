from pydantic import BaseModel, Field

from vcut_workers.contracts.messaging import RetryMetadata
from vcut_workers.worker.errors import ProcessingErrorInfo


class RetryPolicy(BaseModel):
    max_attempts: int = Field(default=3, ge=1)
    initial_backoff_seconds: float = Field(default=1.0, ge=0)
    backoff_multiplier: float = Field(default=2.0, ge=1)
    max_backoff_seconds: float = Field(default=60.0, ge=0)

    def can_retry(self, attempt: int) -> bool:
        return attempt < self.max_attempts

    def metadata_for(self, attempt: int, error: ProcessingErrorInfo) -> RetryMetadata:
        if attempt < 1:
            raise ValueError("attempt must be positive")
        backoff = min(
            self.initial_backoff_seconds * self.backoff_multiplier ** (attempt - 1),
            self.max_backoff_seconds,
        )
        return RetryMetadata(
            attempt=attempt,
            max_attempts=self.max_attempts,
            next_attempt=attempt + 1,
            backoff_seconds=backoff,
            error_code=error.code,
            error_message=error.message,
        )
