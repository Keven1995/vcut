import shutil
import subprocess
from pathlib import Path
from types import SimpleNamespace
from typing import cast
from uuid import UUID

import pytest
from pydantic import ValidationError

from vcut_workers.application.clip_generation import GenerateClipUseCase
from vcut_workers.application.errors import MediaProcessingLimitError
from vcut_workers.application.ports import MediaProcessor, SmartCropAnalyzer
from vcut_workers.application.smart_reframing import (
    RelevantRegionSelector,
    SmartReframingAnalyzer,
    TemporalFaceTracker,
)
from vcut_workers.contracts.clip_generation import ClipGenerationCommand
from vcut_workers.contracts.vision import VisualAnalysisPayload
from vcut_workers.domain.clip_generation import ClipComposition, CropSettings
from vcut_workers.domain.media import VideoAsset, VideoMetadata
from vcut_workers.domain.vision import (
    BoundingBox,
    FaceDetection,
    RegionKind,
    SceneInterval,
    VisualAnalysis,
    VisualRegion,
)
from vcut_workers.infrastructure.storage.memory import InMemoryObjectStorage
from vcut_workers.infrastructure.vision.scene import FFmpegSceneDetector

VIDEO_ID = UUID("44444444-4444-4444-8444-444444444444")
CLIP_ID = UUID("11111111-1111-4111-8111-111111111111")
USER_ID = UUID("22222222-2222-4222-8222-222222222222")
PROJECT_ID = UUID("33333333-3333-4333-8333-333333333333")


def clip_command() -> ClipGenerationCommand:
    return ClipGenerationCommand.model_validate(
        {
            "clipId": CLIP_ID,
            "userId": USER_ID,
            "projectId": PROJECT_ID,
            "videoId": VIDEO_ID,
            "pipelineVersion": 3,
            "editVersion": 2,
            "sourceObjectKey": "users/source/video.mp4",
            "outputObjectKey": "users/clips/clip-v2.mp4",
            "startSeconds": 2,
            "endSeconds": 5,
            "aspectRatio": "9:16",
            "captionPreset": "KARAOKE",
            "captionStyle": {"fontFamily": "DejaVu Sans"},
            "captionCues": [],
        }
    )


def face(timestamp: float, center_x: float, *, track_id: int = 1) -> FaceDetection:
    return FaceDetection(
        timestamp_seconds=timestamp,
        bounding_box=BoundingBox(center_x - 0.1, 0.3, 0.2, 0.2),
        confidence=0.9,
        track_id=track_id,
    )


def test_visual_contract_rejects_non_monotonic_timestamps() -> None:
    with pytest.raises(ValidationError):
        VisualAnalysisPayload.model_validate(
            {
                "durationSeconds": 4,
                "faces": [
                    {
                        "timestampSeconds": 2,
                        "boundingBox": {"x": 0.1, "y": 0.1, "width": 0.2, "height": 0.2},
                        "confidence": 0.9,
                    },
                    {
                        "timestampSeconds": 1,
                        "boundingBox": {"x": 0.1, "y": 0.1, "width": 0.2, "height": 0.2},
                        "confidence": 0.9,
                    },
                ],
            }
        )


def test_tracker_smooths_small_face_oscillations() -> None:
    tracked = TemporalFaceTracker(smoothing=0.25).track((face(0, 0.7), face(1, 0.9), face(2, 0.7)))

    assert tracked[1].bounding_box.center_x == pytest.approx(0.75)
    assert tracked[2].bounding_box.center_x == pytest.approx(0.7375)
    assert [item.timestamp_seconds for item in tracked] == [0, 1, 2]


def test_selector_prioritizes_speaker_region_over_face() -> None:
    analysis = VisualAnalysis(
        duration_seconds=5,
        faces=(face(1, 0.2),),
        regions=(VisualRegion(1, 0.8, 0.4, 1, RegionKind.SPEAKER),),
    )

    crop = RelevantRegionSelector().select(
        analysis,
        start_seconds=0,
        end_seconds=3,
        fallback=CropSettings.centered(),
    )

    assert crop.x == pytest.approx(0.8)


def test_selector_uses_motion_region_when_no_face_is_available() -> None:
    analysis = VisualAnalysis(
        duration_seconds=5,
        regions=(VisualRegion(2, 0.15, 0.6, 0.8, RegionKind.MOTION),),
    )

    crop = RelevantRegionSelector().select(
        analysis,
        start_seconds=0,
        end_seconds=3,
        fallback=CropSettings.centered(),
    )

    assert crop.x == pytest.approx(0.15)


class FakeSceneDetector:
    def detect(
        self,
        source: Path,
        *,
        start_seconds: float,
        end_seconds: float,
        duration_seconds: float,
    ) -> tuple[SceneInterval, ...]:
        del source, duration_seconds
        return (SceneInterval(start_seconds, end_seconds),)


class FakeFaceDetector:
    def __init__(self, detections: tuple[FaceDetection, ...]) -> None:
        self.detections = detections
        self.timestamps: tuple[float, ...] = ()

    def detect(
        self, source: Path, *, timestamps_seconds: tuple[float, ...]
    ) -> tuple[FaceDetection, ...]:
        del source
        self.timestamps = timestamps_seconds
        return self.detections


class RecordingSceneStore:
    def __init__(self) -> None:
        self.saved: tuple[UUID, int, tuple[SceneInterval, ...]] | None = None

    def save(
        self,
        video_id: UUID,
        pipeline_version: int,
        intervals: tuple[SceneInterval, ...],
    ) -> None:
        self.saved = (video_id, pipeline_version, intervals)


def test_smart_reframing_persists_scenes_and_limits_long_video_sampling(tmp_path: Path) -> None:
    source = tmp_path / "source.mp4"
    source.write_bytes(b"fixture")
    face_detector = FakeFaceDetector((face(30, 0.85),))
    store = RecordingSceneStore()
    analyzer = SmartReframingAnalyzer(
        FakeSceneDetector(),
        face_detector,
        frame_interval_seconds=1,
        max_frames=3,
        scene_store=store,
    )

    crop = analyzer.crop_for(
        source,
        video=VideoAsset("videos/long.mp4", 10_000),
        video_id=VIDEO_ID,
        pipeline_version=2,
        start_seconds=30,
        end_seconds=300,
        fallback_x=0.5,
        fallback_y=0.5,
        fallback_zoom=1,
    )

    assert crop[0] == pytest.approx(0.85)
    assert len(face_detector.timestamps) == 3
    assert store.saved is not None
    assert store.saved[0:2] == (VIDEO_ID, 2)


def test_scene_detector_builds_monotonic_intervals_from_ffmpeg_output(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    source = tmp_path / "source.mp4"
    source.write_bytes(b"fixture")

    def fake_run(*args: object, **kwargs: object) -> SimpleNamespace:
        del args, kwargs
        return SimpleNamespace(stderr="pts_time:1.000\npts_time:1.020\npts_time:3.000\n")

    monkeypatch.setattr("vcut_workers.infrastructure.vision.scene.subprocess.run", fake_run)
    intervals = FFmpegSceneDetector().detect(
        source,
        start_seconds=10,
        end_seconds=14,
        duration_seconds=20,
    )

    assert intervals == (
        SceneInterval(10, 11),
        SceneInterval(11, 13),
        SceneInterval(13, 14),
    )


@pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="FFmpeg is required for scene fixtures")
def test_scene_detector_finds_a_known_cut_in_a_real_video(tmp_path: Path) -> None:
    source = tmp_path / "known-cut.mp4"
    subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "color=c=red:s=160x90:r=24:d=1",
            "-f",
            "lavfi",
            "-i",
            "color=c=blue:s=160x90:r=24:d=1",
            "-filter_complex",
            "[0:v][1:v]concat=n=2:v=1:a=0,format=yuv420p[v]",
            "-map",
            "[v]",
            "-c:v",
            "libx264",
            "-t",
            "2",
            str(source),
        ],
        check=True,
        capture_output=True,
        text=True,
    )

    intervals = FFmpegSceneDetector(timeout_seconds=30, threshold=0.2).detect(
        source,
        start_seconds=0,
        end_seconds=2,
        duration_seconds=2,
    )

    assert len(intervals) >= 2
    assert any(interval.start_seconds == pytest.approx(1, abs=0.1) for interval in intervals[1:])


def test_scene_detector_timeout_is_a_sanitized_limit_error(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    source = tmp_path / "source.mp4"
    source.write_bytes(b"fixture")

    def timed_out(*args: object, **kwargs: object) -> SimpleNamespace:
        del kwargs
        raise subprocess.TimeoutExpired(cast(str, args[0]), 1)

    monkeypatch.setattr("vcut_workers.infrastructure.vision.scene.subprocess.run", timed_out)
    with pytest.raises(MediaProcessingLimitError, match="VISION_TIME_LIMIT_EXCEEDED"):
        FFmpegSceneDetector().detect(
            source,
            start_seconds=0,
            end_seconds=2,
            duration_seconds=2,
        )


class BrokenSmartCrop:
    def crop_for(self, *args: object, **kwargs: object) -> tuple[float, float, float]:
        del args, kwargs
        raise RuntimeError("provider unavailable")


class FakeMediaProcessor:
    def __init__(self) -> None:
        self.compositions: list[ClipComposition] = []

    def probe(self, source: Path) -> VideoMetadata:
        if source.name == "source":
            return VideoMetadata("mp4", 10, 1920, 1080, 30, True, "h264", "aac")
        return VideoMetadata("mp4", 3, 1080, 1920, 30, True, "h264", "aac")

    def compose(
        self, source: Path, destination: Path, composition: ClipComposition
    ) -> VideoMetadata:
        del source
        self.compositions.append(composition)
        destination.write_bytes(b"rendered")
        return self.probe(destination)


def test_visual_provider_failure_keeps_deterministic_crop_fallback() -> None:
    storage = InMemoryObjectStorage()
    storage.put("users/source/video.mp4", b"source", "video/mp4")
    processor = FakeMediaProcessor()
    use_case = GenerateClipUseCase(
        storage,
        cast(MediaProcessor, processor),
        smart_reframing=cast(SmartCropAnalyzer, BrokenSmartCrop()),
        smart_reframing_enabled=True,
    )

    use_case.execute(clip_command())

    assert processor.compositions[0].crop_settings == CropSettings.centered()
