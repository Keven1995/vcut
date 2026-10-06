from time import perf_counter
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field

from vcut_workers.application.clip_analysis import GenerateClipCandidatesUseCase
from vcut_workers.contracts.clip_analysis import AnalyzeClipsCommand
from vcut_workers.infrastructure.analysis.deterministic import DeterministicContentAnalyzer


class OfflineEvaluationExample(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    example_id: str = Field(alias="exampleId", min_length=1)
    command: AnalyzeClipsCommand
    expected_has_reliable_candidate: bool = Field(alias="expectedHasReliableCandidate")
    expected_minimum_candidates: Annotated[int, Field(ge=0)] = Field(
        alias="expectedMinimumCandidates"
    )


class OfflineEvaluationReport(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    example_count: Annotated[int, Field(ge=0)] = Field(alias="exampleCount")
    reliable_classification_accuracy: Annotated[float, Field(ge=0, le=1)] = Field(
        alias="reliableClassificationAccuracy"
    )
    correct_candidate_examples: Annotated[int, Field(ge=0)] = Field(
        alias="correctCandidateExamples"
    )
    false_positive_count: Annotated[int, Field(ge=0)] = Field(alias="falsePositiveCount")
    first_candidate_latency_ms: Annotated[float, Field(ge=0)] = Field(
        alias="firstCandidateLatencyMs"
    )


def evaluate_clip_analysis(
    examples: tuple[OfflineEvaluationExample, ...],
) -> OfflineEvaluationReport:
    analyzer = GenerateClipCandidatesUseCase(DeterministicContentAnalyzer())
    classification_matches = 0
    correct_candidates = 0
    false_positives = 0
    first_candidate_latency_ms = 0.0

    for example in examples:
        started = perf_counter()
        result = analyzer.execute(example.command)
        elapsed_ms = (perf_counter() - started) * 1_000
        first_candidate_latency_ms = max(first_candidate_latency_ms, elapsed_ms)
        has_candidate = result.has_reliable_candidate
        classification_matches += has_candidate == example.expected_has_reliable_candidate
        correct_candidates += len(result.candidates) >= example.expected_minimum_candidates
        false_positives += int(not example.expected_has_reliable_candidate and has_candidate)

    count = len(examples)
    return OfflineEvaluationReport(
        exampleCount=count,
        reliableClassificationAccuracy=classification_matches / count if count else 1.0,
        correctCandidateExamples=correct_candidates,
        falsePositiveCount=false_positives,
        firstCandidateLatencyMs=first_candidate_latency_ms,
    )
