import shutil
import subprocess
from pathlib import Path

import pytest

from vcut_workers.application.errors import MediaProcessingLimitError
from vcut_workers.infrastructure.ffmpeg.processor import (
    FFmpegExecutionLimits,
    FFmpegVideoProcessor,
)

pytestmark = pytest.mark.skipif(
    shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None,
    reason="FFmpeg is required for media integration tests",
)


def make_fixture_video(destination: Path, *, with_audio: bool = True) -> None:
    command = [
        "ffmpeg",
        "-y",
        "-f",
        "lavfi",
        "-i",
        "testsrc=size=320x240:rate=24",
    ]
    if with_audio:
        command.extend(
            [
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=1000:sample_rate=48000",
            ]
        )
    command.extend(
        [
            "-t",
            "2",
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
        ]
    )
    if with_audio:
        command.extend(["-c:a", "aac", "-shortest"])
    command.extend(["-metadata:s:v:0", "rotate=90", str(destination)])
    subprocess.run(
        command,
        check=True,
        capture_output=True,
        text=True,
    )


def test_processor_normalizes_audio_and_samples_deterministically(tmp_path: Path) -> None:
    source = tmp_path / "source.mp4"
    normalized = tmp_path / "normalized.mp4"
    audio = tmp_path / "audio.wav"
    thumbnail = tmp_path / "thumbnail.jpg"
    frames = tmp_path / "frames"
    make_fixture_video(source)
    processor = FFmpegVideoProcessor(
        execution_limits=FFmpegExecutionLimits(timeout_seconds=60, max_temp_bytes=10_000_000)
    )

    source_metadata = processor.probe(source)
    normalized_metadata = processor.normalize(source, normalized, target_fps=30)
    audio_metadata = processor.extract_audio(source, audio, sample_rate=16_000, channels=1)
    processor.thumbnail(source, thumbnail, timestamp_seconds=0.5, width=160, height=90)
    frame_paths = processor.sample_frames(
        source,
        frames,
        timestamps_seconds=(0.0, 1.0),
        width=160,
        height=90,
    )

    assert source_metadata.container == "mp4"
    assert normalized_metadata.video_codec == "h264"
    assert normalized_metadata.frame_rate == pytest.approx(30, abs=0.1)
    assert audio_metadata.sample_rate == 16_000
    assert audio_metadata.channels == 1
    assert audio_metadata.duration_seconds == pytest.approx(
        source_metadata.duration_seconds, abs=0.1
    )
    assert thumbnail.stat().st_size > 0
    assert len(frame_paths) == 2
    assert all(path.exists() and path.stat().st_size > 0 for path in frame_paths)


def test_processor_normalizes_video_without_audio(tmp_path: Path) -> None:
    source = tmp_path / "silent-source.mp4"
    normalized = tmp_path / "silent-normalized.mp4"
    make_fixture_video(source, with_audio=False)

    metadata = FFmpegVideoProcessor().normalize(source, normalized, target_fps=30)

    assert metadata.has_audio is False
    assert metadata.audio_codec is None


def test_processor_removes_oversized_temporary_output(tmp_path: Path) -> None:
    source = tmp_path / "source.mp4"
    normalized = tmp_path / "normalized.mp4"
    make_fixture_video(source)
    processor = FFmpegVideoProcessor(
        execution_limits=FFmpegExecutionLimits(timeout_seconds=60, max_temp_bytes=1)
    )

    with pytest.raises(MediaProcessingLimitError, match="TEMPORARY_STORAGE_LIMIT_EXCEEDED"):
        processor.normalize(source, normalized, target_fps=30)

    assert not normalized.exists()


def test_processor_reports_memory_limit(tmp_path: Path) -> None:
    source = tmp_path / "source.mp4"
    normalized = tmp_path / "normalized.mp4"
    make_fixture_video(source)
    processor = FFmpegVideoProcessor(
        execution_limits=FFmpegExecutionLimits(
            timeout_seconds=60,
            max_temp_bytes=10_000_000,
            max_memory_bytes=1,
        )
    )

    with pytest.raises(MediaProcessingLimitError, match="MEMORY_LIMIT_EXCEEDED"):
        processor.normalize(source, normalized, target_fps=30)

    assert not normalized.exists()
