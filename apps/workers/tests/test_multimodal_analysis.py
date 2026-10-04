from pathlib import Path
from uuid import UUID

import pytest
from pydantic import ValidationError

from vcut_workers.application.clip_analysis import (
    GenerateClipCandidatesUseCase,
    segment_transcription,
)
from vcut_workers.config import WorkerSettings
from vcut_workers.contracts.clip_analysis import AnalysisTranscriptSegment, AnalyzeClipsCommand
from vcut_workers.domain.clip_analysis import DurationPreference
from vcut_workers.domain.multimodal import (
    EvidenceModality,
    MultimodalAnalysis,
    MultimodalTopic,
)
from vcut_workers.domain.vision import SceneInterval
from vcut_workers.infrastructure.analysis.deterministic import DeterministicContentAnalyzer
from vcut_workers.infrastructure.analysis.multimodal import (
    DeterministicMultimodalAnalyzer,
    ResilientMultimodalAnalyzer,
    TranscriptFallbackMultimodalAnalyzer,
)

VIDEO_ID = UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")


def command(*, object_key: str | None = "users/test/video.mp4") -> AnalyzeClipsCommand:
    segment = AnalysisTranscriptSegment(
        text="Wow, no way! The boss fight starts right now.",
        start_seconds=1,
        end_seconds=4,
        confidence=0.8,
    )
    return AnalyzeClipsCommand(
        videoId=VIDEO_ID,
        pipelineVersion=1,
        durationSeconds=10,
        language="en",
        text=segment.text,
        segments=(segment,),
        durationPreference=DurationPreference.AUTO,
        objectKey=object_key,
    )


class FakeSceneDetector:
    def detect(
        self,
        source: Path,
        *,
        start_seconds: float,
        end_seconds: float,
        duration_seconds: float,
    ) -> tuple[SceneInterval, ...]:
        del source, duration_seconds
        return (
            SceneInterval(start_seconds, 4),
            SceneInterval(4, end_seconds),
        )


class BrokenSceneDetector:
    def detect(
        self,
        source: Path,
        *,
        start_seconds: float,
        end_seconds: float,
        duration_seconds: float,
    ) -> tuple[SceneInterval, ...]:
        del source, start_seconds, end_seconds, duration_seconds
        raise RuntimeError("fixture detector failure")


def test_deterministic_provider_combines_transcript_and_visual_scene_cues() -> None:
    request = command()
    segments = segment_transcription(request.segments)
    source = Path("fixture-video.mp4")
    analysis = DeterministicMultimodalAnalyzer(FakeSceneDetector()).analyze(
        request, tuple(segments), source
    )

    topics = {finding.topic for finding in analysis.findings}
    modalities = {modality for finding in analysis.findings for modality in finding.evidence}
    assert topics == {
        MultimodalTopic.REACTION,
        MultimodalTopic.GAMEPLAY,
        MultimodalTopic.VISUAL_CONTEXT,
    }
    assert modalities == {EvidenceModality.TRANSCRIPT, EvidenceModality.VISUAL}
    assert analysis.transcription_fallback is False


def test_visual_provider_failure_falls_back_to_transcript_signals(tmp_path: Path) -> None:
    analyzer = ResilientMultimodalAnalyzer(DeterministicMultimodalAnalyzer(BrokenSceneDetector()))
    request = command()
    segments = tuple(segment_transcription(request.segments))
    source = tmp_path / "fixture.mp4"
    source.write_bytes(b"fixture")

    analysis = analyzer.analyze(request, segments, source)

    assert analysis.provider == "transcription-fallback"
    assert analysis.transcription_fallback is True
    assert {finding.topic for finding in analysis.findings} == {
        MultimodalTopic.REACTION,
        MultimodalTopic.GAMEPLAY,
    }
    assert all(finding.evidence == (EvidenceModality.TRANSCRIPT,) for finding in analysis.findings)


def test_optional_multimodal_findings_influence_clip_candidates_without_blocking_fallback() -> None:
    request = command()
    analysis = TranscriptFallbackMultimodalAnalyzer().analyze(
        request, tuple(segment_transcription(request.segments)), Path("not-used")
    )
    enriched = request.model_copy(update={"multimodal_context": analysis.findings})

    result = GenerateClipCandidatesUseCase(DeterministicContentAnalyzer()).execute(enriched)

    assert result.candidates
    assert any("sinais multimodais" in candidate.justification for candidate in result.candidates)


def test_multimodal_context_schema_rejects_invalid_timestamps() -> None:
    with pytest.raises(ValidationError):
        MultimodalAnalysis.model_validate(
            {
                "provider": "fixture",
                "durationSeconds": 3,
                "findings": [
                    {
                        "startSeconds": 2,
                        "endSeconds": 4,
                        "topic": "REACTION",
                        "evidence": ["TRANSCRIPT"],
                        "confidence": 0.7,
                        "summary": "reaction",
                    }
                ],
            }
        )


def test_experimental_analysis_defaults_are_disabled() -> None:
    settings = WorkerSettings()

    assert settings.multimodal_analysis_enabled is False
    assert settings.face_tracking_enabled is False
    assert settings.auto_zoom_enabled is False
