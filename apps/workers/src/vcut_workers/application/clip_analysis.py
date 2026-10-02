from collections.abc import Sequence

from vcut_workers.application.ports import ContentAnalyzer
from vcut_workers.contracts.clip_analysis import (
    AnalysisTranscriptSegment,
    AnalyzeClipsCommand,
    AnalyzeClipsResult,
)
from vcut_workers.domain.clip_analysis import AnalysisProviderResponse, SemanticSegment


def segment_transcription(
    segments: Sequence[AnalysisTranscriptSegment],
    *,
    max_block_seconds: float = 30.0,
    pause_seconds: float = 1.0,
) -> tuple[SemanticSegment, ...]:
    """Split ordered transcript segments at pauses, sentence boundaries, or block limits."""

    if max_block_seconds <= 0 or pause_seconds < 0:
        raise ValueError("segmentation thresholds must be valid")
    blocks: list[SemanticSegment] = []
    current: list[tuple[int, AnalysisTranscriptSegment]] = []
    current_start = 0.0
    current_end = 0.0

    for index, segment in enumerate(segments):
        start = segment.start_seconds
        end = segment.end_seconds
        boundary = bool(current) and (
            start - current_end > pause_seconds
            or end - current_start > max_block_seconds
            or (current[-1][1].text.rstrip().endswith((".", "!", "?")) and start - current_end >= 0.2)
        )
        if boundary:
            blocks.append(_semantic_segment(current, current_start, current_end))
            current = []
        if not current:
            current_start = start
        current.append((index, segment))
        current_end = end

    if current:
        blocks.append(_semantic_segment(current, current_start, current_end))
    return tuple(blocks)


class GenerateClipCandidatesUseCase:
    """Segment a transcript and validate every analyzer response before publication."""

    def __init__(self, analyzer: ContentAnalyzer) -> None:
        self._analyzer = analyzer

    def execute(self, command: AnalyzeClipsCommand) -> AnalyzeClipsResult:
        semantic_segments = segment_transcription(command.segments)
        raw_response = self._analyzer.analyze(
            command,
            tuple(semantic_segments),
        )
        response = AnalysisProviderResponse.model_validate(raw_response)
        return AnalyzeClipsResult(
            provider=response.provider,
            duration_seconds=response.duration_seconds,
            candidates=response.candidates,
            has_reliable_candidate=response.has_reliable_candidate,
        )


def _semantic_segment(
    segments: Sequence[tuple[int, AnalysisTranscriptSegment]], start: float, end: float
) -> SemanticSegment:
    confidences = [
        float(confidence)
        for confidence in (segment.confidence for _, segment in segments)
        if confidence is not None
    ]
    return SemanticSegment(
        text=" ".join(segment.text.strip() for _, segment in segments).strip(),
        start_seconds=start,
        end_seconds=end,
        confidence=sum(confidences) / len(confidences) if confidences else None,
        source_segment_indexes=tuple(index for index, _ in segments),
    )
