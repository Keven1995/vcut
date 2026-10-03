from pathlib import Path
from uuid import UUID

import pytest

from vcut_workers.application.errors import MediaProcessingError
from vcut_workers.application.media_processing import (
    ExtractAudioUseCase,
    GenerateMediaSamplesUseCase,
    MediaPipelineCommand,
    MediaProcessingLimits,
    NormalizeVideoUseCase,
    PrepareMediaPipeline,
)
from vcut_workers.domain.clip_generation import ClipComposition
from vcut_workers.domain.media import AudioMetadata, VideoMetadata
from vcut_workers.infrastructure.storage.memory import InMemoryObjectStorage

USER_ID = UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
PROJECT_ID = UUID("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
VIDEO_ID = UUID("cccccccc-cccc-4ccc-8ccc-cccccccccccc")


class FakeMediaProcessor:
    def __init__(self, *, audio_duration: float = 12.5) -> None:
        self.video = VideoMetadata(
            container="mp4",
            duration_seconds=12.5,
            width=1920,
            height=1080,
            frame_rate=30,
            has_audio=True,
            video_codec="h264",
            audio_codec="aac",
        )
        self.audio = AudioMetadata("wav", audio_duration, 16_000, 1, "pcm_s16le")
        self.normalize_calls = 0
        self.audio_calls = 0
        self.sample_calls = 0
        self.temporary_paths: list[Path] = []

    def probe(self, source: Path) -> VideoMetadata:
        return self.video

    def compose(
        self, source: Path, destination: Path, composition: ClipComposition
    ) -> VideoMetadata:
        del source, composition
        destination.write_bytes(b"composed-video")
        return self.video

    def cut(
        self, source: Path, destination: Path, start_seconds: float, end_seconds: float
    ) -> None:
        del source, destination, start_seconds, end_seconds
        raise NotImplementedError

    def normalize(
        self,
        source: Path,
        destination: Path,
        *,
        target_fps: float,
        video_codec: str,
        audio_codec: str,
    ) -> VideoMetadata:
        del source, target_fps, video_codec, audio_codec
        self.normalize_calls += 1
        destination.write_bytes(b"normalized-video")
        self.temporary_paths.append(destination)
        return self.video

    def extract_audio(
        self,
        source: Path,
        destination: Path,
        *,
        sample_rate: int,
        channels: int,
    ) -> AudioMetadata:
        del source, sample_rate, channels
        self.audio_calls += 1
        destination.write_bytes(b"audio")
        self.temporary_paths.append(destination)
        return self.audio

    def probe_audio(self, source: Path) -> AudioMetadata:
        del source
        return self.audio

    def thumbnail(
        self,
        source: Path,
        destination: Path,
        *,
        timestamp_seconds: float,
        width: int,
        height: int,
    ) -> None:
        del source, timestamp_seconds, width, height
        destination.write_bytes(b"thumbnail")
        self.temporary_paths.append(destination)

    def sample_frames(
        self,
        source: Path,
        destination_directory: Path,
        *,
        timestamps_seconds: tuple[float, ...],
        width: int,
        height: int,
    ) -> tuple[Path, ...]:
        del source, width, height
        self.sample_calls += 1
        destination_directory.mkdir(parents=True, exist_ok=True)
        paths = []
        for index, timestamp in enumerate(timestamps_seconds):
            del timestamp
            path = destination_directory / f"frame-{index:04d}.jpg"
            path.write_bytes(b"frame")
            self.temporary_paths.append(path)
            paths.append(path)
        return tuple(paths)


def command() -> MediaPipelineCommand:
    return MediaPipelineCommand(USER_ID, PROJECT_ID, VIDEO_ID, 2, "source/original.mp4")


def test_pipeline_creates_scoped_versioned_artifacts_and_reuses_them() -> None:
    storage = InMemoryObjectStorage()
    storage.put(command().source_object_key, b"source", "video/mp4")
    processor = FakeMediaProcessor()
    limits = MediaProcessingLimits(max_audio_duration_drift_seconds=0.1)
    normalize = NormalizeVideoUseCase(storage, processor, limits)
    extract_audio = ExtractAudioUseCase(storage, processor, limits)
    samples = GenerateMediaSamplesUseCase(storage, processor, limits)
    pipeline = PrepareMediaPipeline(normalize, extract_audio, samples)

    first = pipeline.execute(command(), (0.0, 4.0, 8.0))
    second = pipeline.execute(command(), (0.0, 4.0, 8.0))

    assert [stage.stage_name for stage in first] == ["NORMALIZE", "EXTRACT_AUDIO", "MEDIA_SAMPLES"]
    assert all(stage.pipeline_version == 2 for stage in first)
    assert all(
        "users/" in key and "/v2/" in key for stage in first for key in stage.output_object_keys
    )
    assert processor.normalize_calls == 1
    assert processor.audio_calls == 1
    assert processor.sample_calls == 1
    assert [stage.metadata["reused"] for stage in second] == [True, True, True]
    assert all(not path.exists() for path in processor.temporary_paths)


def test_audio_stage_rejects_duration_drift() -> None:
    storage = InMemoryObjectStorage()
    pipeline_command = command()
    input_key = f"users/{USER_ID}/projects/{PROJECT_ID}/normalized/{VIDEO_ID}/v2/video.mp4"
    storage.put(input_key, b"normalized", "video/mp4")

    with pytest.raises(MediaProcessingError, match="AUDIO_DURATION_MISMATCH"):
        ExtractAudioUseCase(
            storage,
            FakeMediaProcessor(audio_duration=10),
            MediaProcessingLimits(max_audio_duration_drift_seconds=0.1),
        ).execute(pipeline_command)


def test_samples_reject_timestamps_outside_the_video() -> None:
    storage = InMemoryObjectStorage()
    pipeline_command = command()
    input_key = f"users/{USER_ID}/projects/{PROJECT_ID}/normalized/{VIDEO_ID}/v2/video.mp4"
    storage.put(input_key, b"normalized", "video/mp4")

    with pytest.raises(MediaProcessingError, match="SAMPLE_TIMESTAMP_OUT_OF_RANGE"):
        GenerateMediaSamplesUseCase(storage, FakeMediaProcessor()).execute(
            pipeline_command, (13.0,)
        )


def test_normalization_rejects_media_above_duration_limit() -> None:
    storage = InMemoryObjectStorage()
    storage.put(command().source_object_key, b"source", "video/mp4")

    with pytest.raises(MediaProcessingError, match="DURATION_LIMIT_EXCEEDED"):
        NormalizeVideoUseCase(
            storage,
            FakeMediaProcessor(),
            MediaProcessingLimits(max_duration_seconds=10),
        ).execute(command())
