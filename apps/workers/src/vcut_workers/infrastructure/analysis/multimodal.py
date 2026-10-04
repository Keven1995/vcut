import re
from collections.abc import Iterable
from pathlib import Path

from vcut_workers.application.ports import MultimodalAnalyzer
from vcut_workers.contracts.clip_analysis import AnalyzeClipsCommand
from vcut_workers.domain.clip_analysis import SemanticSegment
from vcut_workers.domain.multimodal import (
    EvidenceModality,
    MultimodalAnalysis,
    MultimodalFinding,
    MultimodalTopic,
)
from vcut_workers.infrastructure.vision.scene import FFmpegSceneDetector

_REACTION_TERMS = re.compile(
    r"\b(wow|whoa|oh my|no way|incredible|amazing|incr[ií]vel|caramba|n[aã]o acredito|haha|risos)\b",
    re.IGNORECASE,
)
_GAMEPLAY_TERMS = re.compile(
    r"\b(game|gameplay|jogo|partida|boss|level|round|enemy|inimigo|miss[aã]o|fase|player)\b",
    re.IGNORECASE,
)


class DeterministicMultimodalAnalyzer:
    """Combine transcript cues with bounded FFmpeg scene-change observations."""

    def __init__(
        self,
        scene_detector: FFmpegSceneDetector,
        *,
        max_visual_findings: int = 40,
    ) -> None:
        if max_visual_findings < 1:
            raise ValueError("max_visual_findings must be positive")
        self._scene_detector = scene_detector
        self._max_visual_findings = max_visual_findings

    def analyze(
        self,
        command: AnalyzeClipsCommand,
        segments: tuple[SemanticSegment, ...],
        source: Path,
    ) -> MultimodalAnalysis:
        findings = list(_transcript_findings(segments))
        scenes = self._scene_detector.detect(
            source,
            start_seconds=0,
            end_seconds=command.duration_seconds,
            duration_seconds=command.duration_seconds,
        )
        for scene in scenes[1 : self._max_visual_findings + 1]:
            findings.append(
                MultimodalFinding(
                    startSeconds=scene.start_seconds,
                    endSeconds=scene.end_seconds,
                    topic=MultimodalTopic.VISUAL_CONTEXT,
                    evidence=(EvidenceModality.VISUAL,),
                    confidence=scene.confidence,
                    summary="Visual scene transition detected.",
                )
            )
        findings.sort(key=lambda finding: (finding.start_seconds, finding.end_seconds))
        return MultimodalAnalysis(
            provider="deterministic-multimodal",
            durationSeconds=command.duration_seconds,
            findings=tuple(findings),
        )


class TranscriptFallbackMultimodalAnalyzer:
    """Keep reaction/gameplay hints available when the visual processor fails."""

    def analyze(
        self,
        command: AnalyzeClipsCommand,
        segments: tuple[SemanticSegment, ...],
        source: Path,
    ) -> MultimodalAnalysis:
        del source
        return MultimodalAnalysis(
            provider="transcription-fallback",
            durationSeconds=command.duration_seconds,
            findings=tuple(_transcript_findings(segments)),
            transcriptionFallback=True,
        )


class ResilientMultimodalAnalyzer:
    def __init__(
        self,
        primary: MultimodalAnalyzer,
        fallback: MultimodalAnalyzer | None = None,
    ) -> None:
        self._primary = primary
        self._fallback = fallback or TranscriptFallbackMultimodalAnalyzer()

    def analyze(
        self,
        command: AnalyzeClipsCommand,
        segments: tuple[SemanticSegment, ...],
        source: Path,
    ) -> MultimodalAnalysis:
        try:
            return self._primary.analyze(command, segments, source)
        except Exception:
            return self._fallback.analyze(command, segments, source)


def _transcript_findings(segments: Iterable[SemanticSegment]) -> tuple[MultimodalFinding, ...]:
    findings: list[MultimodalFinding] = []
    for segment in segments:
        if _REACTION_TERMS.search(segment.text):
            findings.append(
                MultimodalFinding(
                    startSeconds=segment.start_seconds,
                    endSeconds=segment.end_seconds,
                    topic=MultimodalTopic.REACTION,
                    evidence=(EvidenceModality.TRANSCRIPT,),
                    confidence=segment.confidence or 0.6,
                    summary="Transcript contains a reaction cue.",
                )
            )
        if _GAMEPLAY_TERMS.search(segment.text):
            findings.append(
                MultimodalFinding(
                    startSeconds=segment.start_seconds,
                    endSeconds=segment.end_seconds,
                    topic=MultimodalTopic.GAMEPLAY,
                    evidence=(EvidenceModality.TRANSCRIPT,),
                    confidence=segment.confidence or 0.6,
                    summary="Transcript contains a gameplay cue.",
                )
            )
    findings.sort(key=lambda finding: (finding.start_seconds, finding.end_seconds))
    return tuple(findings)


__all__ = [
    "DeterministicMultimodalAnalyzer",
    "ResilientMultimodalAnalyzer",
    "TranscriptFallbackMultimodalAnalyzer",
]
