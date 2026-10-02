import json
from pathlib import Path

from vcut_workers.evaluation.clip_analysis import (
    OfflineEvaluationExample,
    evaluate_clip_analysis,
)


def test_offline_clip_analysis_baseline_has_no_false_positive() -> None:
    fixture_path = Path(__file__).parents[3] / "tests" / "fixtures" / "clip-analysis-dataset.json"
    payload = json.loads(fixture_path.read_text(encoding="utf-8"))
    examples = tuple(OfflineEvaluationExample.model_validate(item) for item in payload)

    report = evaluate_clip_analysis(examples)

    assert report.example_count == 2
    assert report.reliable_classification_accuracy == 1.0
    assert report.correct_candidate_examples == 2
    assert report.false_positive_count == 0
    assert report.first_candidate_latency_ms < 100
