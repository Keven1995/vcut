from pathlib import Path

import pytest

from vcut_workers.domain.media import VideoAsset, VisionSignal
from vcut_workers.infrastructure.ffmpeg import FFmpegVideoProcessor
from vcut_workers.infrastructure.storage.memory import InMemoryObjectStorage
from vcut_workers.infrastructure.vision.deterministic import DeterministicVisionAnalyzer


def test_domain_values_validate_external_boundaries() -> None:
    with pytest.raises(ValueError):
        VideoAsset(object_key="", duration_seconds=1)

    with pytest.raises(ValueError):
        VisionSignal(kind="face", timestamp_seconds=1, confidence=2)


def test_local_adapters_have_explicit_contracts() -> None:
    storage = InMemoryObjectStorage()
    storage.put("videos/source.mp4")
    video = VideoAsset(object_key="videos/source.mp4", duration_seconds=10)

    assert storage.exists(video.object_key)
    assert DeterministicVisionAnalyzer().analyze(video) == ()


def test_ffmpeg_adapter_rejects_invalid_interval() -> None:
    processor = FFmpegVideoProcessor()

    with pytest.raises(ValueError):
        processor.cut(Path("source.mp4"), Path("clip.mp4"), 5, 5)
