from collections.abc import Iterable
from importlib import import_module
from pathlib import Path
from typing import Protocol, cast

from vcut_workers.config import WorkerSettings
from vcut_workers.domain.transcription import TranscriptionProviderResponse


class WhisperWordBackend(Protocol):
    @property
    def word(self) -> str: ...

    @property
    def start(self) -> float: ...

    @property
    def end(self) -> float: ...

    @property
    def probability(self) -> float: ...


class WhisperSegmentBackend(Protocol):
    @property
    def text(self) -> str: ...

    @property
    def start(self) -> float: ...

    @property
    def end(self) -> float: ...

    @property
    def words(self) -> Iterable[WhisperWordBackend] | None: ...


class WhisperInfoBackend(Protocol):
    @property
    def language(self) -> str: ...

    @property
    def duration(self) -> float: ...


class WhisperBackend(Protocol):
    def transcribe(
        self,
        audio_path: str,
        *,
        language: str | None,
        beam_size: int,
    ) -> tuple[Iterable[WhisperSegmentBackend], WhisperInfoBackend]: ...


class _FasterWhisperBackend:
    def __init__(self, model: object) -> None:
        self._model = model

    def transcribe(
        self,
        audio_path: str,
        *,
        language: str | None,
        beam_size: int,
    ) -> tuple[Iterable[WhisperSegmentBackend], WhisperInfoBackend]:
        transcribe = getattr(self._model, "transcribe", None)
        if not callable(transcribe):
            raise RuntimeError("faster-whisper model does not expose transcribe")
        raw_result = transcribe(audio_path, language=language, beam_size=beam_size)
        if not isinstance(raw_result, tuple) or len(raw_result) != 2:
            raise RuntimeError("faster-whisper returned an invalid result")
        raw_segments, raw_info = raw_result
        if isinstance(raw_segments, str | bytes) or not isinstance(raw_segments, Iterable):
            raise RuntimeError("faster-whisper returned invalid segments")
        return (
            cast(Iterable[WhisperSegmentBackend], raw_segments),
            cast(WhisperInfoBackend, raw_info),
        )


class WhisperTranscriptionProvider:
    """Optional local faster-whisper adapter with lazy model loading."""

    def __init__(
        self,
        model_size: str = "small",
        device: str = "cpu",
        compute_type: str = "int8",
        beam_size: int = 5,
        default_language: str | None = None,
        backend: WhisperBackend | None = None,
    ) -> None:
        if not model_size or not device or not compute_type:
            raise ValueError("Whisper model configuration must not be blank")
        if beam_size < 1:
            raise ValueError("beam_size must be positive")
        self._model_size = model_size
        self._device = device
        self._compute_type = compute_type
        self._beam_size = beam_size
        self._default_language = default_language
        self._backend = backend

    @classmethod
    def from_settings(
        cls,
        settings: WorkerSettings,
        *,
        backend: WhisperBackend | None = None,
    ) -> "WhisperTranscriptionProvider":
        return cls(
            model_size=settings.whisper_model_size,
            device=settings.whisper_device,
            compute_type=settings.whisper_compute_type,
            beam_size=settings.whisper_beam_size,
            default_language=settings.transcription_language,
            backend=backend,
        )

    def transcribe(
        self,
        audio_path: Path,
        *,
        language: str | None = None,
    ) -> TranscriptionProviderResponse:
        if not audio_path.is_file():
            raise FileNotFoundError(audio_path)
        backend = self._backend or self._load_backend()
        segments, info = backend.transcribe(
            str(audio_path),
            language=language or self._default_language,
            beam_size=self._beam_size,
        )

        normalized_segments: list[dict[str, object]] = []
        transcript_parts: list[str] = []
        maximum_end = 0.0
        for segment in segments:
            words = tuple(
                {
                    "text": word.word.strip(),
                    "start": word.start,
                    "end": word.end,
                    "confidence": word.probability,
                }
                for word in (segment.words or ())
            )
            segment_text = segment.text.strip()
            if segment_text:
                transcript_parts.append(segment_text)
            segment_confidence = _average_confidence(words)
            normalized_segments.append(
                {
                    "text": segment_text,
                    "start": segment.start,
                    "end": segment.end,
                    "confidence": segment_confidence,
                    "words": words,
                }
            )
            maximum_end = max(maximum_end, segment.end)

        info_duration = _finite_number(getattr(info, "duration", None))
        duration = max(maximum_end, info_duration or 0.0)
        provider_language = getattr(info, "language", None)
        selected_language = (
            language
            or self._default_language
            or (provider_language if isinstance(provider_language, str) else None)
            or "und"
        )
        return TranscriptionProviderResponse.model_validate(
            {
                "language": selected_language,
                "text": " ".join(transcript_parts),
                "durationSeconds": duration,
                "segments": normalized_segments,
            }
        )

    def _load_backend(self) -> WhisperBackend:
        try:
            module = import_module("faster_whisper")
        except ModuleNotFoundError as error:
            raise RuntimeError(
                "faster-whisper is optional; install it to use the local Whisper provider"
            ) from error
        model_factory = getattr(module, "WhisperModel", None)
        if not callable(model_factory):
            raise RuntimeError("faster-whisper does not expose WhisperModel")
        model = model_factory(
            self._model_size,
            device=self._device,
            compute_type=self._compute_type,
        )
        self._backend = _FasterWhisperBackend(model)
        return self._backend


def _finite_number(value: object) -> float | None:
    if isinstance(value, int | float) and not isinstance(value, bool):
        return float(value)
    return None


def _average_confidence(words: tuple[dict[str, object], ...]) -> float | None:
    values = [
        value for value in (word.get("confidence") for word in words) if isinstance(value, float)
    ]
    return sum(values) / len(values) if values else None
