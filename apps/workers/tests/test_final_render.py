import shutil
import subprocess
from pathlib import Path
from typing import cast
from uuid import UUID

import pytest
from pydantic import ValidationError

from vcut_workers.application.clip_generation import ClipGenerationLimits
from vcut_workers.application.final_render import FinalRenderUseCase
from vcut_workers.application.ports import MediaProcessor
from vcut_workers.contracts.final_render import FinalRenderCommand, FinalRenderResult
from vcut_workers.domain.clip_generation import AspectRatio
from vcut_workers.domain.media import VideoMetadata
from vcut_workers.infrastructure.ffmpeg.processor import FFmpegExecutionLimits, FFmpegVideoProcessor
from vcut_workers.infrastructure.storage.memory import InMemoryObjectStorage

RENDER_ID = UUID("11111111-1111-4111-8111-111111111111")
CLIP_ID = UUID("22222222-2222-4222-8222-222222222222")
USER_ID = UUID("33333333-3333-4333-8333-333333333333")
PROJECT_ID = UUID("44444444-4444-4444-8444-444444444444")
VIDEO_ID = UUID("55555555-5555-4555-8555-555555555555")


def command(**overrides: object) -> FinalRenderCommand:
    payload: dict[str, object] = {
        "renderId": RENDER_ID,
        "clipId": CLIP_ID,
        "userId": USER_ID,
        "projectId": PROJECT_ID,
        "videoId": VIDEO_ID,
        "pipelineVersion": 1,
        "editVersion": 4,
        "sourceObjectKey": "users/source/video.mp4",
        "outputObjectKey": "users/final/video.mp4",
        "thumbnailObjectKey": "users/final/thumbnail.jpg",
        "startSeconds": 1.0,
        "endSeconds": 4.0,
        "aspectRatio": "9:16",
        "captionPreset": "MINIMAL",
        "captionStyle": {"fontFamily": "DejaVu Sans"},
        "captionCues": [],
    }
    payload.update(overrides)
    return FinalRenderCommand.model_validate(payload)


class FakeMediaProcessor:
    def __init__(self) -> None:
        self.compositions: list[object] = []
        self.thumbnails: list[Path] = []

    def probe(self, source: Path) -> VideoMetadata:
        if source.name == "source":
            return VideoMetadata(
                container="mp4",
                duration_seconds=10,
                width=1920,
                height=1080,
                frame_rate=30,
                has_audio=True,
                video_codec="h264",
                audio_codec="aac",
            )
        return VideoMetadata(
            container="mp4",
            duration_seconds=3,
            width=1080,
            height=1920,
            frame_rate=30,
            has_audio=True,
            video_codec="h264",
            audio_codec="aac",
        )

    def compose(self, source: Path, destination: Path, composition: object) -> VideoMetadata:
        del source
        self.compositions.append(composition)
        destination.write_bytes(b"rendered")
        return self.probe(destination)

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
        self.thumbnails.append(destination)


def test_final_render_uploads_video_and_thumbnail_once() -> None:
    storage = InMemoryObjectStorage()
    storage.put("users/source/video.mp4", b"source", "video/mp4")
    processor = FakeMediaProcessor()
    use_case = FinalRenderUseCase(
        storage,
        cast(MediaProcessor, processor),
        ClipGenerationLimits(max_input_size_bytes=100),
    )

    first = use_case.execute(command())
    second = use_case.execute(command())

    assert first == second
    assert first.status == "READY"
    assert storage.exists("users/final/video.mp4")
    assert storage.exists("users/final/thumbnail.jpg")
    assert len(processor.compositions) == 1
    assert len(processor.thumbnails) == 1


def test_final_render_command_rejects_unsafe_thumbnail_key() -> None:
    with pytest.raises(ValidationError):
        command(thumbnailObjectKey="users/../thumbnail.jpg")


def test_final_render_ready_result_requires_thumbnail() -> None:
    with pytest.raises(ValidationError):
        FinalRenderResult(
            render_id=RENDER_ID,
            clip_id=CLIP_ID,
            edit_version=4,
            status="READY",
            duration_seconds=3,
            width=1080,
            height=1920,
            aspect_ratio=AspectRatio.VERTICAL,
            output_object_key="users/final/video.mp4",
        )


@pytest.mark.skipif(
    shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None,
    reason="FFmpeg is required for the final render integration test",
)
@pytest.mark.parametrize(
    ("aspect_ratio", "width", "height"),
    [("9:16", 180, 320), ("16:9", 320, 180)],
)
def test_final_render_produces_ffprobe_valid_artifact(
    tmp_path: Path, aspect_ratio: str, width: int, height: int
) -> None:
    source = tmp_path / "source.mp4"
    subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "testsrc=size=320x240:rate=24",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=1000:sample_rate=48000",
            "-t",
            "2",
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-shortest",
            str(source),
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    storage = InMemoryObjectStorage()
    storage.put("users/source/video.mp4", source.read_bytes(), "video/mp4")
    processor = FFmpegVideoProcessor(
        execution_limits=FFmpegExecutionLimits(timeout_seconds=60, max_temp_bytes=50_000_000)
    )
    use_case = FinalRenderUseCase(
        storage,
        processor,
        ClipGenerationLimits(
            vertical_width=180,
            vertical_height=320,
            horizontal_width=320,
            horizontal_height=180,
        ),
    )
    result = use_case.execute(
        command(
            aspectRatio=aspect_ratio,
            outputObjectKey=f"users/final/{aspect_ratio.replace(':', '-')}.mp4",
            thumbnailObjectKey=f"users/final/{aspect_ratio.replace(':', '-')}.jpg",
            endSeconds=1.5,
        )
    )
    output = tmp_path / "final.mp4"
    storage.download(result.output_object_key or "", output)

    metadata = processor.probe(output)

    assert result.status == "READY"
    assert (metadata.width, metadata.height) == (width, height)
    assert metadata.sample_aspect_ratio == "1:1"
    assert metadata.duration_seconds == pytest.approx(0.5, abs=0.15)
