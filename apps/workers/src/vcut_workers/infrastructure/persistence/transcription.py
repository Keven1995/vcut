import threading
from pathlib import Path
from tempfile import TemporaryDirectory
from uuid import UUID

from vcut_workers.application.ports import WritableObjectStorage
from vcut_workers.domain.transcription import TranscriptionResult


class InMemoryTranscriptionResultStore:
    """Process-local result store for tests and local development."""

    def __init__(self) -> None:
        self._results: dict[tuple[UUID, int], TranscriptionResult] = {}
        self._lock = threading.Lock()

    def get(self, video_id: UUID, pipeline_version: int) -> TranscriptionResult | None:
        with self._lock:
            return self._results.get((video_id, pipeline_version))

    def save(self, result: TranscriptionResult) -> TranscriptionResult:
        key = (result.video_id, result.pipeline_version)
        with self._lock:
            existing = self._results.get(key)
            if existing is not None:
                if existing != result:
                    raise ValueError("a different transcription already exists for this version")
                return existing
            self._results[key] = result
            return result


class ObjectStorageTranscriptionResultStore:
    """Persist one immutable JSON result per video and pipeline version."""

    def __init__(self, object_storage: WritableObjectStorage) -> None:
        self._object_storage = object_storage

    def get(self, video_id: UUID, pipeline_version: int) -> TranscriptionResult | None:
        object_key = transcription_result_key(video_id, pipeline_version)
        if self._object_storage.head(object_key) is None:
            return None
        with TemporaryDirectory(prefix="vcut-transcription-result-") as directory:
            source = Path(directory) / "result.json"
            self._object_storage.download(object_key, source)
            return TranscriptionResult.model_validate_json(source.read_text(encoding="utf-8"))

    def save(self, result: TranscriptionResult) -> TranscriptionResult:
        existing = self.get(result.video_id, result.pipeline_version)
        if existing is not None:
            if existing != result:
                raise ValueError("a different transcription already exists for this version")
            return existing

        object_key = transcription_result_key(result.video_id, result.pipeline_version)
        with TemporaryDirectory(prefix="vcut-transcription-result-") as directory:
            source = Path(directory) / "result.json"
            source.write_text(result.model_dump_json(by_alias=True), encoding="utf-8")
            self._object_storage.upload(source, object_key, "application/json")
        return result


def transcription_result_key(video_id: UUID, pipeline_version: int) -> str:
    if pipeline_version < 1:
        raise ValueError("pipeline_version must be positive")
    return f"transcriptions/{video_id}/v{pipeline_version}/result.json"
