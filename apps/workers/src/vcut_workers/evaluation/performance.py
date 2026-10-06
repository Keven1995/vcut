from math import ceil

from pydantic import BaseModel, ConfigDict, Field


class WorkerPerformanceSample(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    workload_id: str = Field(alias="workloadId", min_length=1)
    source_duration_seconds: float = Field(gt=0)
    first_cut_latency_ms: float = Field(ge=0)
    render_duration_seconds: float = Field(ge=0)
    total_processing_seconds: float = Field(ge=0)
    output_bytes: int = Field(ge=0)
    estimated_cost: float = Field(ge=0)


class WorkerPerformanceSummary(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    workload_id: str = Field(alias="workloadId", min_length=1)
    sample_count: int = Field(alias="sampleCount", ge=1)
    first_cut_p50_ms: float = Field(alias="firstCutP50Ms", ge=0)
    first_cut_p95_ms: float = Field(alias="firstCutP95Ms", ge=0)
    render_p50_seconds: float = Field(alias="renderP50Seconds", ge=0)
    render_p95_seconds: float = Field(alias="renderP95Seconds", ge=0)
    throughput_clips_per_minute: float = Field(alias="throughputClipsPerMinute", ge=0)
    processing_seconds_per_processed_minute: float = Field(
        alias="processingSecondsPerProcessedMinute", ge=0
    )
    estimated_cost_per_processed_minute: float = Field(
        alias="estimatedCostPerProcessedMinute", ge=0
    )
    total_output_bytes: int = Field(alias="totalOutputBytes", ge=0)


def summarize_worker_performance(
    samples: tuple[WorkerPerformanceSample, ...],
) -> WorkerPerformanceSummary:
    if not samples:
        raise ValueError("at least one worker performance sample is required")
    workload_ids = {sample.workload_id for sample in samples}
    if len(workload_ids) != 1:
        raise ValueError("all worker performance samples must have the same workload id")

    total_seconds = sum(sample.total_processing_seconds for sample in samples)
    processed_minutes = sum(sample.source_duration_seconds for sample in samples) / 60
    return WorkerPerformanceSummary(
        workloadId=samples[0].workload_id,
        sampleCount=len(samples),
        firstCutP50Ms=_percentile(tuple(sample.first_cut_latency_ms for sample in samples), 0.50),
        firstCutP95Ms=_percentile(tuple(sample.first_cut_latency_ms for sample in samples), 0.95),
        renderP50Seconds=_percentile(
            tuple(sample.render_duration_seconds for sample in samples), 0.50
        ),
        renderP95Seconds=_percentile(
            tuple(sample.render_duration_seconds for sample in samples), 0.95
        ),
        throughputClipsPerMinute=(len(samples) * 60 / total_seconds if total_seconds else 0),
        processingSecondsPerProcessedMinute=(
            total_seconds / processed_minutes if processed_minutes else 0
        ),
        estimatedCostPerProcessedMinute=(
            sum(sample.estimated_cost for sample in samples) / processed_minutes
            if processed_minutes
            else 0
        ),
        totalOutputBytes=sum(sample.output_bytes for sample in samples),
    )


def _percentile(values: tuple[float, ...], percentile: float) -> float:
    ordered = sorted(values)
    index = max(0, ceil(len(ordered) * percentile) - 1)
    return ordered[index]
