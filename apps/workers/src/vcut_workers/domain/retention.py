from dataclasses import dataclass
from enum import StrEnum
from pathlib import PurePosixPath
from uuid import UUID


class RetainedObjectKind(StrEnum):
    ORIGINAL = "ORIGINAL"
    NORMALIZED = "NORMALIZED"
    AUDIO = "AUDIO"
    FRAMES = "FRAMES"
    PREVIEW = "PREVIEW"
    FINAL = "FINAL"
    THUMBNAIL = "THUMBNAIL"
    FAILED_JOB_ARTIFACT = "FAILED_JOB_ARTIFACT"


@dataclass(frozen=True)
class ExpiredObject:
    id: UUID
    object_key: str
    delete_attempts: int

    def __post_init__(self) -> None:
        path = PurePosixPath(self.object_key)
        if (
            not self.object_key.startswith("users/")
            or ".." in path.parts
            or "\\" in self.object_key
            or self.delete_attempts < 0
        ):
            raise ValueError("expired object metadata is invalid")


@dataclass(frozen=True)
class RetentionCleanupReport:
    dry_run: bool
    scanned: int
    deleted: int
    failed: int
    failure_codes: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if min(self.scanned, self.deleted, self.failed) < 0:
            raise ValueError("cleanup counts must not be negative")
        if self.deleted + self.failed > self.scanned:
            raise ValueError("cleanup result counts exceed scanned objects")


__all__ = ["ExpiredObject", "RetainedObjectKind", "RetentionCleanupReport"]
