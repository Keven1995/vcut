from __future__ import annotations

import argparse
import json
import os
import platform
import shutil
import subprocess
import sys
import tempfile
import time
from decimal import Decimal
from pathlib import Path
from typing import Literal
from uuid import uuid4

from pydantic import BaseModel, ConfigDict, Field

from vcut_workers.application.clip_analysis import GenerateClipCandidatesUseCase
from vcut_workers.application.media_processing import (
    ExtractAudioUseCase,
    MediaPipelineCommand,
    MediaProcessingLimits,
    NormalizeVideoUseCase,
    artifact_key,
)
from vcut_workers.application.transcription import (
    TranscribeAudioUseCase,
    TranscriptionCommand,
)
from vcut_workers.contracts.clip_analysis import (
    AnalysisTranscriptSegment,
    AnalyzeClipsCommand,
)
from vcut_workers.domain.clip_generation import (
    AspectRatio,
    CaptionCue,
    CaptionPreset,
    CaptionTrack,
    ClipComposition,
    caption_style_for_preset,
)
from vcut_workers.evaluation.performance import (
    WorkerPerformanceSample,
    summarize_worker_performance,
)
from vcut_workers.infrastructure.analysis.deterministic import DeterministicContentAnalyzer
from vcut_workers.infrastructure.ffmpeg.processor import (
    FFmpegExecutionLimits,
    FFmpegVideoProcessor,
)
from vcut_workers.infrastructure.persistence.transcription import (
    InMemoryTranscriptionResultStore,
)
from vcut_workers.infrastructure.storage.memory import InMemoryObjectStorage
from vcut_workers.infrastructure.transcription.fake import DeterministicTranscriptionProvider


class Workload(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    id: str = Field(min_length=1)
    width: int = Field(gt=0)
    height: int = Field(gt=0)
    duration_seconds: int = Field(alias="durationSeconds", gt=0, le=90)
    frequency_hz: int = Field(alias="frequencyHz", gt=0)
    output_aspect_ratio: Literal["9:16", "16:9"] = Field(alias="outputAspectRatio")


class WorkloadManifest(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    baseline_version: str = Field(alias="baselineVersion", min_length=1)
    iterations: int = Field(ge=1, le=100)
    workloads: tuple[Workload, ...] = Field(min_length=1)


def main() -> int:
    parser = argparse.ArgumentParser(description="Measure the Vcut worker performance baseline.")
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path(__file__).with_name("workloads-v1.json"),
    )
    parser.add_argument("--iterations", type=int)
    arguments = parser.parse_args()
    manifest = WorkloadManifest.model_validate_json(arguments.manifest.read_text(encoding="utf-8"))
    iterations = arguments.iterations or manifest.iterations
    if iterations < 1 or iterations > 100:
        parser.error("--iterations must be between 1 and 100")

    ffmpeg = os.getenv("FFMPEG_BINARY", "ffmpeg")
    ffmpeg_version = _ffmpeg_version(ffmpeg)
    cpu_rate = Decimal(os.getenv("COST_CPU_PER_SECOND", "0"))
    transcription_rate = Decimal(os.getenv("COST_TRANSCRIPTION_PER_MINUTE", "0"))
    processor = FFmpegVideoProcessor(
        ffmpeg,
        execution_limits=FFmpegExecutionLimits(
            timeout_seconds=120,
            max_temp_bytes=536_870_912,
            max_memory_bytes=1_073_741_824,
        ),
    )

    summaries = []
    for workload in manifest.workloads:
        samples = tuple(
            _run_sample(workload, iteration, processor, cpu_rate, transcription_rate)
            for iteration in range(iterations)
        )
        summaries.append(summarize_worker_performance(samples).model_dump(by_alias=True))

    report = {
        "baselineVersion": manifest.baseline_version,
        "iterationsPerWorkload": iterations,
        "pythonVersion": platform.python_version(),
        "platform": platform.platform(),
        "ffmpegVersion": ffmpeg_version,
        "costRates": {
            "cpuPerSecond": str(cpu_rate),
            "transcriptionPerMinute": str(transcription_rate),
            "cpuSecondsAreWallClockProxy": True,
            "monetaryRatesConfigured": cpu_rate > 0 or transcription_rate > 0,
        },
        "workloads": summaries,
    }
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


def _run_sample(
    workload: Workload,
    iteration: int,
    processor: FFmpegVideoProcessor,
    cpu_rate: Decimal,
    transcription_rate: Decimal,
) -> WorkerPerformanceSample:
    video_id = uuid4()
    user_id = uuid4()
    project_id = uuid4()
    pipeline_command = MediaPipelineCommand(
        user_id,
        project_id,
        video_id,
        iteration + 1,
        f"users/{user_id}/projects/{project_id}/source/{video_id}/original.mp4",
    )
    storage = InMemoryObjectStorage()

    with tempfile.TemporaryDirectory(prefix="vcut-worker-benchmark-") as temporary:
        directory = Path(temporary)
        source_path = directory / "source.mp4"
        normalized_path = directory / "normalized.mp4"
        rendered_path = directory / "first-cut.mp4"
        _make_fixture_video(source_path, workload)
        storage.put(pipeline_command.source_object_key, source_path.read_bytes(), "video/mp4")

        started = time.perf_counter()
        NormalizeVideoUseCase(
            storage,
            processor,
            MediaProcessingLimits(max_input_size_bytes=536_870_912),
        ).execute(pipeline_command)
        normalized_key = artifact_key(pipeline_command, "normalized", "video.mp4")
        storage.download(normalized_key, normalized_path)
        ExtractAudioUseCase(
            storage,
            processor,
            MediaProcessingLimits(max_input_size_bytes=536_870_912),
        ).execute(pipeline_command)
        audio_key = artifact_key(pipeline_command, "audio", "transcription.wav")
        transcription = TranscribeAudioUseCase(
            DeterministicTranscriptionProvider(),
            InMemoryTranscriptionResultStore(),
            storage,
            provider_name="fake",
        ).execute(
            TranscriptionCommand(
                video_id,
                iteration + 1,
                audio_object_key=audio_key,
                language="en",
            )
        )
        video_metadata = processor.probe(normalized_path)
        segments = tuple(
            AnalysisTranscriptSegment(
                text=segment.text,
                start_seconds=segment.start_seconds,
                end_seconds=segment.end_seconds,
                confidence=segment.confidence,
            )
            for segment in transcription.segments
            if segment.end_seconds <= video_metadata.duration_seconds
        )
        analysis_command = AnalyzeClipsCommand(
            video_id=video_id,
            pipeline_version=iteration + 1,
            duration_seconds=video_metadata.duration_seconds,
            language=transcription.language,
            text=" ".join(segment.text for segment in segments),
            segments=segments,
        )
        analysis = GenerateClipCandidatesUseCase(DeterministicContentAnalyzer()).execute(
            analysis_command
        )
        first_cut_latency_ms = (time.perf_counter() - started) * 1_000
        if not analysis.candidates:
            raise RuntimeError(f"workload {workload.id} produced no cut candidate")

        candidate = analysis.candidates[0]
        clip_duration = candidate.end_seconds - candidate.start_seconds
        aspect_ratio = AspectRatio(workload.output_aspect_ratio)
        width, height = (180, 320) if aspect_ratio is AspectRatio.VERTICAL else (320, 180)
        composition = ClipComposition(
            start_seconds=candidate.start_seconds,
            end_seconds=candidate.end_seconds,
            aspect_ratio=aspect_ratio,
            caption_track=CaptionTrack(
                cues=(
                    CaptionCue(
                        sequence=0,
                        text=candidate.title,
                        start_seconds=0,
                        end_seconds=clip_duration,
                    ),
                ),
                duration_seconds=clip_duration,
            ),
            caption_style=caption_style_for_preset(CaptionPreset.MINIMAL),
            width=width,
            height=height,
        )
        render_started = time.perf_counter()
        processor.compose(normalized_path, rendered_path, composition)
        render_duration_seconds = time.perf_counter() - render_started
        total_processing_seconds = time.perf_counter() - started
        processed_minutes = workload.duration_seconds / 60
        estimated_cost = float(
            Decimal(str(total_processing_seconds)) * cpu_rate
            + Decimal(str(processed_minutes)) * transcription_rate
        )
        return WorkerPerformanceSample(
            workloadId=workload.id,
            source_duration_seconds=video_metadata.duration_seconds,
            first_cut_latency_ms=first_cut_latency_ms,
            render_duration_seconds=render_duration_seconds,
            total_processing_seconds=total_processing_seconds,
            output_bytes=rendered_path.stat().st_size,
            estimated_cost=estimated_cost,
        )


def _make_fixture_video(destination: Path, workload: Workload) -> None:
    command = [
        os.getenv("FFMPEG_BINARY", "ffmpeg"),
        "-hide_banner",
        "-loglevel",
        "error",
        "-y",
        "-f",
        "lavfi",
        "-i",
        f"testsrc=size={workload.width}x{workload.height}:rate=24",
        "-f",
        "lavfi",
        "-i",
        f"sine=frequency={workload.frequency_hz}:sample_rate=48000",
        "-t",
        str(workload.duration_seconds),
        "-c:v",
        "libx264",
        "-pix_fmt",
        "yuv420p",
        "-c:a",
        "aac",
        "-shortest",
        str(destination),
    ]
    subprocess.run(command, check=True, capture_output=True, text=True, timeout=60)


def _ffmpeg_version(binary: str) -> str:
    if shutil.which(binary) is None:
        raise RuntimeError(f"FFmpeg executable was not found: {binary}")
    completed = subprocess.run(
        [binary, "-version"], check=True, capture_output=True, text=True, timeout=10
    )
    return completed.stdout.splitlines()[0]


if __name__ == "__main__":
    sys.exit(main())
