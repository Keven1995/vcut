import pytest

from vcut_workers.evaluation.performance import (
    WorkerPerformanceSample,
    summarize_worker_performance,
)


def test_performance_summary_reports_latency_throughput_and_cost_per_minute() -> None:
    samples = (
        WorkerPerformanceSample(
            workloadId="horizontal-short",
            source_duration_seconds=60,
            first_cut_latency_ms=100,
            render_duration_seconds=1,
            total_processing_seconds=2,
            output_bytes=100,
            estimated_cost=0.1,
        ),
        WorkerPerformanceSample(
            workloadId="horizontal-short",
            source_duration_seconds=60,
            first_cut_latency_ms=200,
            render_duration_seconds=2,
            total_processing_seconds=3,
            output_bytes=200,
            estimated_cost=0.2,
        ),
        WorkerPerformanceSample(
            workloadId="horizontal-short",
            source_duration_seconds=60,
            first_cut_latency_ms=300,
            render_duration_seconds=3,
            total_processing_seconds=4,
            output_bytes=300,
            estimated_cost=0.3,
        ),
    )

    summary = summarize_worker_performance(samples)

    assert summary.first_cut_p50_ms == 200
    assert summary.first_cut_p95_ms == 300
    assert summary.render_p50_seconds == 2
    assert summary.render_p95_seconds == 3
    assert summary.throughput_clips_per_minute == pytest.approx(60 / 3)
    assert summary.processing_seconds_per_processed_minute == pytest.approx(3)
    assert summary.estimated_cost_per_processed_minute == pytest.approx(0.2)
    assert summary.total_output_bytes == 600


def test_performance_summary_rejects_mixed_workloads() -> None:
    first = WorkerPerformanceSample(
        workloadId="horizontal-short",
        source_duration_seconds=2,
        first_cut_latency_ms=10,
        render_duration_seconds=0.5,
        total_processing_seconds=1,
        output_bytes=100,
        estimated_cost=0,
    )
    second = first.model_copy(update={"workload_id": "vertical-short"})

    with pytest.raises(ValueError, match="same workload id"):
        summarize_worker_performance((first, second))
