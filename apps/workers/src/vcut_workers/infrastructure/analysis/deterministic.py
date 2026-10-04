from uuid import NAMESPACE_URL, uuid5

from vcut_workers.contracts.clip_analysis import AnalyzeClipsCommand
from vcut_workers.domain.clip_analysis import (
    AnalysisProviderResponse,
    CandidateScores,
    CandidateVariant,
    ClipCandidate,
    DurationPreference,
    SemanticSegment,
)
from vcut_workers.domain.multimodal import MultimodalFinding, MultimodalTopic


class DeterministicContentAnalyzer:
    """Generate stable candidates without a hosted AI provider."""

    def analyze(
        self,
        command: AnalyzeClipsCommand,
        segments: tuple[SemanticSegment, ...],
    ) -> AnalysisProviderResponse:
        reliable = tuple(
            segment
            for segment in segments
            if _is_reliable(segment)
            and segment.end_seconds <= command.duration_seconds
        )
        candidates: list[ClipCandidate] = []
        for index, segment in enumerate(reliable):
            group_id = uuid5(
                NAMESPACE_URL,
                f"vcut:clip-group:{command.video_id}:{command.pipeline_version}:{index}",
            )
            contextual_start = reliable[max(0, index - 1)].start_seconds
            contextual_end = reliable[min(len(reliable) - 1, index + 1)].end_seconds
            intervals = (
                (CandidateVariant.SHORT, segment.start_seconds, segment.end_seconds),
                (CandidateVariant.COMPLETE, segment.start_seconds, segment.end_seconds),
                (CandidateVariant.CONTEXTUAL, contextual_start, contextual_end),
            )
            for variant, start, end in intervals:
                if end - start > 90:
                    continue
                findings = tuple(
                    finding
                    for finding in command.multimodal_context
                    if finding.start_seconds < segment.end_seconds
                    and finding.end_seconds > segment.start_seconds
                )
                scores = _scores(segment, end - start, command, findings)
                justification = (
                    "Trecho com fala suficiente, timestamps validos e fronteira semantica deterministica."
                    if not findings
                    else "Trecho validado por transcricao e sinais multimodais basicos de contexto."
                )
                candidates.append(
                    ClipCandidate(
                        id=uuid5(
                            NAMESPACE_URL,
                            f"vcut:clip:{command.video_id}:{command.pipeline_version}:{group_id}:{variant.value}",
                        ),
                        group_id=group_id,
                        video_id=command.video_id,
                        pipeline_version=command.pipeline_version,
                        variant=variant,
                        start_seconds=start,
                        end_seconds=end,
                        title=_title(segment.text),
                        description=segment.text,
                        justification=justification,
                        scores=scores,
                        internal_score=_internal_score(scores, end - start, command),
                    )
                )
        candidates.sort(key=lambda candidate: (-candidate.internal_score, candidate.start_seconds, candidate.variant.value))
        return AnalysisProviderResponse(
            provider="deterministic",
            duration_seconds=command.duration_seconds,
            candidates=tuple(candidates[:20]),
            has_reliable_candidate=bool(candidates),
        )


class FallbackContentAnalyzer(DeterministicContentAnalyzer):
    """Explicit fallback adapter that keeps the pipeline available without AI."""

    def analyze(
        self,
        command: AnalyzeClipsCommand,
        segments: tuple[SemanticSegment, ...],
    ) -> AnalysisProviderResponse:
        response = super().analyze(command, segments)
        return response.model_copy(update={"provider": "heuristic-fallback"})


def _is_reliable(segment: SemanticSegment) -> bool:
    text = segment.text.strip(" .,!?:;\t\n")
    duration = segment.end_seconds - segment.start_seconds
    return len(text) >= 8 and duration >= 1.0 and (segment.confidence is None or segment.confidence >= 0.5)


def _scores(
    segment: SemanticSegment,
    duration: float,
    command: AnalyzeClipsCommand,
    multimodal_findings: tuple[MultimodalFinding, ...] = (),
) -> CandidateScores:
    confidence = segment.confidence if segment.confidence is not None else 0.6
    punctuation = 1.0 if segment.text.rstrip().endswith((".", "!", "?")) else 0.65
    independence = min(1.0, max(0.4, duration / 4.0))
    engagement = min(1.0, max(0.35, len(segment.text) / 120.0))
    target = _target_seconds(command.duration_preference, command.custom_duration_seconds)
    context = 0.75 if target is None else max(0.35, 1 - abs(duration - target) / target)
    visual_confidence = max(
        (
            finding.confidence
            for finding in multimodal_findings
            if finding.topic is MultimodalTopic.VISUAL_CONTEXT
        ),
        default=0,
    )
    if visual_confidence > 0:
        context = max(context, 0.5 + 0.4 * visual_confidence)
    interaction_confidence = max(
        (
            finding.confidence
            for finding in multimodal_findings
            if finding.topic in (MultimodalTopic.REACTION, MultimodalTopic.GAMEPLAY)
        ),
        default=0,
    )
    engagement = max(engagement, interaction_confidence)
    return CandidateScores(
        hook=confidence,
        context=context,
        development=confidence * 0.9,
        payoff=punctuation,
        independence=independence,
        engagement=engagement,
    )


def _internal_score(scores: CandidateScores, duration: float, command: AnalyzeClipsCommand) -> float:
    target = _target_seconds(command.duration_preference, command.custom_duration_seconds)
    duration_alignment = 0.75 if target is None else max(0.25, 1 - abs(duration - target) / target)
    return min(
        1.0,
        0.2 * scores.hook
        + 0.15 * scores.context
        + 0.15 * scores.development
        + 0.2 * scores.payoff
        + 0.15 * scores.independence
        + 0.1 * scores.engagement
        + 0.05 * duration_alignment,
    )


def _target_seconds(preference: DurationPreference, custom: float | None) -> float | None:
    return {
        DurationPreference.SHORT: 15.0,
        DurationPreference.MEDIUM: 30.0,
        DurationPreference.LONG: 60.0,
        DurationPreference.CUSTOM: custom,
        DurationPreference.AUTO: None,
    }[preference]


def _title(text: str) -> str:
    normalized = " ".join(text.split())
    return normalized[:157] + "..." if len(normalized) > 160 else normalized
