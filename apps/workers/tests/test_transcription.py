from dataclasses import dataclass
from pathlib import Path
from tempfile import TemporaryDirectory
from uuid import UUID

import pytest
from pydantic import ValidationError

from vcut_workers.application.transcription import (
    TranscribeAudioUseCase,
    TranscriptionCommand,
)
from vcut_workers.domain.transcription import (
    TranscriptionProviderResponse,
    TranscriptionResult,
    TranscriptionSegment,
    TranscriptionWord,
)
from vcut_workers.infrastructure.persistence.transcription import (
    InMemoryTranscriptionResultStore,
    ObjectStorageTranscriptionResultStore,
)
from vcut_workers.infrastructure.storage.memory import InMemoryObjectStorage
from vcut_workers.infrastructure.transcription.fake import (
    DeterministicTranscriptionProvider,
)
from vcut_workers.infrastructure.transcription.whisper import WhisperTranscriptionProvider

VIDEO_ID = UUID("cccccccc-cccc-4ccc-8ccc-cccccccccccc")


def valid_response() -> TranscriptionProviderResponse:
    return TranscriptionProviderResponse(
        language="en",
        text="hello world",
        duration_seconds=2.0,
        confidence=0.9,
        segments=(
            TranscriptionSegment(
                text="hello world",
                start=0.0,
                end=2.0,
                confidence=0.9,
                words=(
                    TranscriptionWord(text="hello", start=0.0, end=1.0, confidence=0.9),
                    TranscriptionWord(text="world", start=1.0, end=2.0, confidence=0.9),
                ),
            ),
        ),
    )


def test_transcription_serialization_uses_api_aliases_and_round_trips() -> None:
    result = TranscriptionResult(
        video_id=VIDEO_ID,
        pipeline_version=3,
        provider="fake",
        language="pt-BR",
        text="ola mundo",
        duration_seconds=2.0,
        segments=(
            TranscriptionSegment(
                text="ola mundo",
                start=0.0,
                end=2.0,
                words=(
                    TranscriptionWord(text="ola", start=0.0, end=1.0),
                    TranscriptionWord(text="mundo", start=1.0, end=2.0),
                ),
            ),
        ),
    )

    payload = result.model_dump(by_alias=True, mode="json")
    restored = TranscriptionResult.model_validate(payload)

    assert payload["videoId"] == str(VIDEO_ID)
    assert payload["pipelineVersion"] == 3
    assert payload["segments"][0]["start"] == 0.0
    assert payload["segments"][0]["words"][0]["end"] == 1.0
    assert restored == result


def test_provider_payload_rejects_invalid_ranges_and_untyped_values() -> None:
    with pytest.raises(ValidationError):
        TranscriptionProviderResponse.model_validate(
            {
                "language": "en",
                "text": "invalid",
                "durationSeconds": 1.0,
                "segments": [{"text": "invalid", "start": 0.5, "end": 0.5}],
            }
        )

    with pytest.raises(ValidationError):
        TranscriptionProviderResponse.model_validate(
            {
                "language": "en",
                "text": "invalid",
                "durationSeconds": 1.0,
                "segments": "not-a-list",
            }
        )


class InvalidPayloadProvider:
    def transcribe(self, audio_path: Path, *, language: str | None = None) -> object:
        del audio_path, language
        return {
            "language": "en",
            "text": "invalid",
            "durationSeconds": 1.0,
            "segments": [{"text": "invalid", "start": 0.0, "end": 2.0}],
        }


def test_use_case_validates_provider_payload_before_persistence() -> None:
    store = InMemoryTranscriptionResultStore()
    use_case = TranscribeAudioUseCase(InvalidPayloadProvider(), store)
    with TemporaryDirectory() as directory:
        audio_path = Path(directory) / "audio.wav"
        audio_path.write_bytes(b"audio")

        with pytest.raises(ValidationError):
            use_case.execute(TranscriptionCommand(VIDEO_ID, 1, audio_path=audio_path))

    assert store.get(VIDEO_ID, 1) is None


def test_word_must_stay_inside_segment_range() -> None:
    with pytest.raises(ValidationError):
        TranscriptionSegment(
            text="hello",
            start=0.0,
            end=1.0,
            words=(TranscriptionWord(text="hello", start=0.5, end=1.5),),
        )


def test_deterministic_provider_returns_the_same_fixture_for_the_same_audio() -> None:
    provider = DeterministicTranscriptionProvider()
    with TemporaryDirectory() as directory:
        first_path = Path(directory) / "first.wav"
        second_path = Path(directory) / "second.wav"
        first_path.write_bytes(b"same-audio")
        second_path.write_bytes(b"same-audio")

        first = provider.transcribe(first_path)
        second = provider.transcribe(second_path)

    assert first == second
    assert first.segments[0].words[0].start_seconds == 0.0


@dataclass(frozen=True)
class StubWord:
    word: str
    start: float
    end: float
    probability: float


@dataclass(frozen=True)
class StubSegment:
    text: str
    start: float
    end: float
    words: tuple[StubWord, ...]


@dataclass(frozen=True)
class StubInfo:
    language: str
    duration: float


class StubWhisperBackend:
    def __init__(self) -> None:
        self.calls = 0

    def transcribe(
        self,
        audio_path: str,
        *,
        language: str | None,
        beam_size: int,
    ) -> tuple[tuple[StubSegment, ...], StubInfo]:
        assert Path(audio_path).is_file()
        assert language == "en"
        assert beam_size == 3
        self.calls += 1
        return (
            (
                StubSegment(
                    text=" hello",
                    start=0.0,
                    end=1.0,
                    words=(StubWord(" hello", 0.0, 1.0, 0.88),),
                ),
            ),
            StubInfo(language="en", duration=1.0),
        )


def test_whisper_adapter_uses_injected_backend_without_importing_faster_whisper() -> None:
    backend = StubWhisperBackend()
    provider = WhisperTranscriptionProvider(
        beam_size=3,
        default_language="en",
        backend=backend,
    )
    with TemporaryDirectory() as directory:
        audio_path = Path(directory) / "audio.wav"
        audio_path.write_bytes(b"audio")
        response = provider.transcribe(audio_path)

    assert backend.calls == 1
    assert response.text == "hello"
    assert response.segments[0].words[0].confidence == 0.88
    assert response.segments[0].end_seconds <= response.duration_seconds


class CountingProvider:
    def __init__(self) -> None:
        self.calls = 0

    def transcribe(self, audio_path: Path, *, language: str | None = None) -> object:
        del audio_path, language
        self.calls += 1
        return valid_response()


class RetryProvider(CountingProvider):
    def transcribe(self, audio_path: Path, *, language: str | None = None) -> object:
        self.calls += 1
        if self.calls == 1:
            raise RuntimeError("temporary provider failure")
        return valid_response()


def test_transcription_is_idempotent_and_keeps_previous_pipeline_versions() -> None:
    provider = CountingProvider()
    store = InMemoryTranscriptionResultStore()
    use_case = TranscribeAudioUseCase(provider, store, provider_name="fake")

    with TemporaryDirectory() as directory:
        audio_path = Path(directory) / "audio.wav"
        audio_path.write_bytes(b"audio")
        first = use_case.execute(TranscriptionCommand(VIDEO_ID, 1, audio_path=audio_path))
        repeated = use_case.execute(TranscriptionCommand(VIDEO_ID, 1, audio_path=audio_path))
        newer = use_case.execute(TranscriptionCommand(VIDEO_ID, 2, audio_path=audio_path))

    assert first == repeated
    assert first.pipeline_version == 1
    assert newer.pipeline_version == 2
    assert store.get(VIDEO_ID, 1) == first
    assert store.get(VIDEO_ID, 2) == newer
    assert provider.calls == 2


def test_transcription_retry_does_not_persist_partial_work() -> None:
    provider = RetryProvider()
    store = InMemoryTranscriptionResultStore()
    use_case = TranscribeAudioUseCase(provider, store)

    with TemporaryDirectory() as directory:
        audio_path = Path(directory) / "audio.wav"
        audio_path.write_bytes(b"audio")
        command = TranscriptionCommand(VIDEO_ID, 1, audio_path=audio_path)
        with pytest.raises(RuntimeError):
            use_case.execute(command)
        assert store.get(VIDEO_ID, 1) is None
        retried = use_case.execute(
            command
        )
        repeated = use_case.execute(
            command
        )

    assert retried == repeated
    assert provider.calls == 2


def test_transcription_can_download_audio_from_storage_and_persist_versioned_json() -> None:
    object_storage = InMemoryObjectStorage()
    object_storage.put("audio/video.wav", b"audio", "audio/wav")
    result_store = ObjectStorageTranscriptionResultStore(object_storage)
    use_case = TranscribeAudioUseCase(
        DeterministicTranscriptionProvider(),
        result_store,
        object_storage,
        provider_name="fake",
    )

    result = use_case.execute(
        TranscriptionCommand(
            VIDEO_ID,
            4,
            audio_object_key="audio/video.wav",
        )
    )

    assert result_store.get(VIDEO_ID, 4) == result
    assert object_storage.exists("transcriptions/cccccccc-cccc-4ccc-8ccc-cccccccccccc/v4/result.json")
