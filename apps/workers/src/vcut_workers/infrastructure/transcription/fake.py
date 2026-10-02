from hashlib import sha256
from pathlib import Path

from vcut_workers.domain.transcription import TranscriptionProviderResponse


class DeterministicTranscriptionProvider:
    """Generate a stable transcript fixture from the audio bytes."""

    def transcribe(
        self,
        audio_path: Path,
        *,
        language: str | None = None,
    ) -> TranscriptionProviderResponse:
        if not audio_path.is_file():
            raise FileNotFoundError(audio_path)
        digest = sha256(audio_path.read_bytes()).hexdigest()[:8]
        selected_language = language or "en"
        first_text = "deterministic audio"
        second_text = f"fixture {digest}"
        return TranscriptionProviderResponse.model_validate(
            {
                "language": selected_language,
                "text": f"{first_text} {second_text}",
                "durationSeconds": 4.0,
                "confidence": 0.95,
                "segments": (
                    {
                        "text": first_text,
                        "start": 0.0,
                        "end": 2.0,
                        "confidence": 0.95,
                        "words": (
                            {
                                "text": "deterministic",
                                "start": 0.0,
                                "end": 1.0,
                                "confidence": 0.96,
                            },
                            {"text": "audio", "start": 1.0, "end": 2.0, "confidence": 0.94},
                        ),
                    },
                    {
                        "text": second_text,
                        "start": 2.0,
                        "end": 4.0,
                        "confidence": 0.95,
                        "words": (
                            {"text": "fixture", "start": 2.0, "end": 3.0, "confidence": 0.95},
                            {"text": digest, "start": 3.0, "end": 4.0, "confidence": 0.95},
                        ),
                    },
                ),
            }
        )


FakeTranscriptionProvider = DeterministicTranscriptionProvider
