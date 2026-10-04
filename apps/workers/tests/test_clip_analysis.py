from typing import cast
from uuid import UUID

import pytest
from pydantic import ValidationError

from vcut_workers.application.clip_analysis import (
    GenerateClipCandidatesUseCase,
    segment_transcription,
)
from vcut_workers.application.ports import ContentAnalyzer
from vcut_workers.contracts.clip_analysis import (
    AnalysisTranscriptSegment,
    AnalyzeClipsCommand,
    ClipCommand,
)
from vcut_workers.domain.clip_analysis import CandidateVariant, DurationPreference
from vcut_workers.infrastructure.analysis.deterministic import (
    DeterministicContentAnalyzer,
    FallbackContentAnalyzer,
)

VIDEO_ID = UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")


def command(
    segments: tuple[AnalysisTranscriptSegment, ...],
    *,
    preference: DurationPreference = DurationPreference.AUTO,
    custom_duration: float | None = None,
) -> AnalyzeClipsCommand:
    return AnalyzeClipsCommand(
        video_id=VIDEO_ID,
        pipeline_version=2,
        duration_seconds=12.0,
        language="pt-BR",
        text=" ".join(segment.text for segment in segments),
        segments=segments,
        duration_preference=preference,
        custom_duration_seconds=custom_duration,
    )


def reliable_segments() -> tuple[AnalysisTranscriptSegment, ...]:
    return (
        AnalysisTranscriptSegment(
            text="Hoje vamos mostrar o resultado.",
            start_seconds=0.4,
            end_seconds=2.1,
            confidence=0.96,
        ),
        AnalysisTranscriptSegment(
            text="A parte importante acontece neste momento.",
            start_seconds=2.4,
            end_seconds=5.8,
            confidence=0.91,
        ),
        AnalysisTranscriptSegment(
            text="Até a próxima.",
            start_seconds=6.1,
            end_seconds=7.4,
            confidence=0.88,
        ),
    )


def test_segmentation_preserves_boundaries_and_source_indexes() -> None:
    segments = reliable_segments()

    result = segment_transcription(segments)

    assert len(result) == 3
    assert result[0].start_seconds == 0.4
    assert result[1].source_segment_indexes == (1,)
    assert result[2].end_seconds == 7.4


def test_deterministic_analyzer_returns_stable_ranked_variants() -> None:
    use_case = GenerateClipCandidatesUseCase(DeterministicContentAnalyzer())
    request = command(reliable_segments())

    first = use_case.execute(request)
    second = use_case.execute(request)

    assert first == second
    assert first.has_reliable_candidate is True
    assert {candidate.variant for candidate in first.candidates} == {
        CandidateVariant.SHORT,
        CandidateVariant.COMPLETE,
        CandidateVariant.CONTEXTUAL,
    }
    assert all(candidate.end_seconds > candidate.start_seconds for candidate in first.candidates)
    assert all(candidate.end_seconds <= request.duration_seconds for candidate in first.candidates)
    assert "internalScore" in first.model_dump(by_alias=True)["candidates"][0]
    assert "scores" in first.model_dump(by_alias=True)["candidates"][0]


def test_fallback_provider_keeps_analysis_available_without_external_provider() -> None:
    result = GenerateClipCandidatesUseCase(FallbackContentAnalyzer()).execute(
        command(reliable_segments())
    )

    assert result.provider == "heuristic-fallback"
    assert result.candidates


class IncompleteAnalyzer:
    def analyze(self, command: AnalyzeClipsCommand, segments: object) -> object:
        del segments
        return {
            "provider": "external",
            "durationSeconds": command.duration_seconds,
            "hasReliableCandidate": True,
            "candidates": [{"id": "not-a-complete-candidate"}],
        }


def test_use_case_rejects_incomplete_analysis_provider_output() -> None:
    use_case = GenerateClipCandidatesUseCase(cast(ContentAnalyzer, IncompleteAnalyzer()))

    with pytest.raises(ValidationError):
        use_case.execute(command(reliable_segments()))


class MeteredAnalyzer:
    def analyze(self, request: AnalyzeClipsCommand, segments: object) -> object:
        del segments
        return {
            "provider": "metered",
            "durationSeconds": request.duration_seconds,
            "candidates": [],
            "hasReliableCandidate": False,
            "usageMetrics": {"llmTokens": 123},
        }


def test_usage_metrics_are_preserved_in_the_published_analysis_result() -> None:
    result = GenerateClipCandidatesUseCase(cast(ContentAnalyzer, MeteredAnalyzer())).execute(
        command(reliable_segments())
    )

    assert result.usage_metrics is not None
    assert result.usage_metrics.llm_tokens == 123


def test_low_confidence_short_transcript_has_no_reliable_candidate() -> None:
    result = GenerateClipCandidatesUseCase(DeterministicContentAnalyzer()).execute(
        command(
            (
                AnalysisTranscriptSegment(
                    text="...",
                    start_seconds=1.2,
                    end_seconds=1.8,
                    confidence=0.22,
                ),
            )
        )
    )

    assert result.has_reliable_candidate is False
    assert result.candidates == ()


def test_custom_duration_requires_value_and_validates_limits() -> None:
    with pytest.raises(ValidationError):
        command(reliable_segments(), preference=DurationPreference.CUSTOM)

    with pytest.raises(ValidationError):
        command(
            reliable_segments(),
            preference=DurationPreference.CUSTOM,
            custom_duration=91.0,
        )


def test_clip_command_rejects_unsafe_unexpected_and_invalid_values() -> None:
    valid = ClipCommand(
        video_id=VIDEO_ID,
        start_seconds=0.0,
        end_seconds=4.0,
        aspect_ratio="9:16",
        candidate_variant=CandidateVariant.COMPLETE,
    )
    assert valid.aspect_ratio == "9:16"

    with pytest.raises(ValidationError):
        ClipCommand(
            video_id=VIDEO_ID,
            start_seconds=0.0,
            end_seconds=4.0,
            aspect_ratio="rm -rf /",
            candidate_variant=CandidateVariant.COMPLETE,
        )

    payload = valid.model_dump()
    payload["shell"] = "unexpected"
    with pytest.raises(ValidationError):
        ClipCommand.model_validate(payload)
