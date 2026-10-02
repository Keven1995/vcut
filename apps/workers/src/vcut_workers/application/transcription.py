from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from tempfile import TemporaryDirectory
from uuid import UUID

from vcut_workers.application.ports import (
    ObjectStorage,
    TranscriptionProvider,
    TranscriptionResultStore,
)
from vcut_workers.domain.transcription import (
    TranscriptionProviderResponse,
    TranscriptionResult,
)
from vcut_workers.infrastructure.persistence.transcription import (
    InMemoryTranscriptionResultStore,
)


@dataclass(frozen=True)
class TranscriptionCommand:
    video_id: UUID
    pipeline_version: int
    audio_path: Path | None = None
    audio_object_key: str | None = None
    language: str | None = None

    def __post_init__(self) -> None:
        if self.pipeline_version < 1:
            raise ValueError("pipeline_version must be positive")
        if (self.audio_path is None) == (self.audio_object_key is None):
            raise ValueError("exactly one audio source must be provided")
        if self.audio_object_key is not None:
            if not self.audio_object_key or ".." in PurePosixPath(self.audio_object_key).parts:
                raise ValueError("audio_object_key must be a safe non-empty path")
        if self.language is not None and not self.language.strip():
            raise ValueError("language must not be blank")


class TranscribeAudioUseCase:
    """Run one versioned transcription without replacing a valid prior result."""

    def __init__(
        self,
        provider: TranscriptionProvider,
        result_store: TranscriptionResultStore | None = None,
        object_storage: ObjectStorage | None = None,
        provider_name: str = "configured",
    ) -> None:
        if not provider_name:
            raise ValueError("provider_name must not be empty")
        self._provider = provider
        self._result_store = result_store or InMemoryTranscriptionResultStore()
        self._object_storage = object_storage
        self._provider_name = provider_name

    def execute(self, command: TranscriptionCommand) -> TranscriptionResult:
        existing = self._result_store.get(command.video_id, command.pipeline_version)
        if existing is not None:
            return existing

        with self._materialize_audio(command) as audio_path:
            raw_response = self._provider.transcribe(audio_path, language=command.language)
            # Every provider response is treated as untrusted before persistence.
            response = TranscriptionProviderResponse.model_validate(raw_response)

        result = TranscriptionResult.model_validate(
            {
                **response.model_dump(by_alias=True),
                "videoId": str(command.video_id),
                "pipelineVersion": command.pipeline_version,
                "provider": self._provider_name,
            }
        )
        return self._result_store.save(result)

    @contextmanager
    def _materialize_audio(self, command: TranscriptionCommand) -> Iterator[Path]:
        if command.audio_path is not None:
            if not command.audio_path.is_file():
                raise FileNotFoundError(command.audio_path)
            yield command.audio_path
            return

        if self._object_storage is None or command.audio_object_key is None:
            raise ValueError("object_storage is required for an audio object key")
        if self._object_storage.head(command.audio_object_key) is None:
            raise FileNotFoundError(command.audio_object_key)

        with TemporaryDirectory(prefix="vcut-transcription-") as directory:
            destination = Path(directory) / "audio"
            self._object_storage.download(command.audio_object_key, destination)
            yield destination


# Short aliases keep the application boundary easy to discover for callers.
TranscriptionUseCase = TranscribeAudioUseCase
TranscribeCommand = TranscriptionCommand
