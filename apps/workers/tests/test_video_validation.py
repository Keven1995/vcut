from pathlib import Path
from uuid import UUID

from vcut_workers.application.video_validation import (
    ValidateUploadedVideoUseCase,
)
from vcut_workers.contracts.video_validation import ValidateVideoCommand
from vcut_workers.domain.media import ObjectMetadata, VideoMetadata


class FakeStorage:
    def __init__(self, metadata: ObjectMetadata | None) -> None:
        self.metadata = metadata

    def exists(self, object_key: str) -> bool:
        return self.metadata is not None and self.metadata.object_key == object_key

    def head(self, object_key: str) -> ObjectMetadata | None:
        return self.metadata

    def download(self, object_key: str, destination: Path) -> None:
        destination.write_bytes(b"video")


class FakeProcessor:
    def __init__(self, metadata: VideoMetadata) -> None:
        self.metadata = metadata

    def extract_audio(self, source: Path, destination: Path) -> None:
        raise NotImplementedError

    def cut(
        self, source: Path, destination: Path, start_seconds: float, end_seconds: float
    ) -> None:
        raise NotImplementedError

    def probe(self, source: Path) -> VideoMetadata:
        return self.metadata


def command(content_type: str = "video/mp4", size_bytes: int = 5) -> ValidateVideoCommand:
    return ValidateVideoCommand(
        video_id=UUID("11111111-1111-4111-8111-111111111111"),
        object_key="users/user/projects/project/source/video/original.mp4",
        original_filename="original.mp4",
        declared_content_type=content_type,
        declared_size_bytes=size_bytes,
    )


def metadata(
    container: str = "mp4",
    video_codec: str = "h264",
    width: int = 1920,
    height: int = 1080,
    has_audio: bool = True,
    audio_codec: str | None = "aac",
) -> VideoMetadata:
    return VideoMetadata(
        container=container,
        duration_seconds=12.5,
        width=width,
        height=height,
        frame_rate=29.97,
        has_audio=has_audio,
        video_codec=video_codec,
        audio_codec=audio_codec,
    )


def test_validation_returns_authoritative_media_metadata() -> None:
    storage = FakeStorage(ObjectMetadata(command().object_key, 5, "video/mp4"))
    result = ValidateUploadedVideoUseCase(storage, FakeProcessor(metadata())).execute(command())

    assert result.status == "READY"
    assert result.actual_size_bytes == 5
    assert result.duration_seconds == 12.5
    assert result.width == 1920
    assert result.height == 1080
    assert result.frame_rate == 29.97
    assert result.has_audio is True


def test_validation_rejects_size_mismatch_before_probe() -> None:
    storage = FakeStorage(ObjectMetadata(command().object_key, 6, "video/mp4"))
    result = ValidateUploadedVideoUseCase(storage, FakeProcessor(metadata())).execute(command())

    assert result.status == "REJECTED"
    assert result.failure_code == "SIZE_MISMATCH"


def test_validation_rejects_declared_mime_that_does_not_match_container() -> None:
    storage = FakeStorage(ObjectMetadata(command("video/webm").object_key, 5, "video/webm"))
    result = ValidateUploadedVideoUseCase(
        storage,
        FakeProcessor(metadata()),
    ).execute(command("video/webm"))

    assert result.status == "REJECTED"
    assert result.failure_code == "CONTENT_TYPE_MISMATCH"


def test_validation_rejects_unsupported_codec() -> None:
    storage = FakeStorage(ObjectMetadata(command().object_key, 5, "video/mp4"))
    result = ValidateUploadedVideoUseCase(
        storage, FakeProcessor(metadata(video_codec="mpeg4"))
    ).execute(command())

    assert result.status == "REJECTED"
    assert result.failure_code == "VIDEO_CODEC_NOT_SUPPORTED"


def test_validation_rejects_invalid_resolution() -> None:
    storage = FakeStorage(ObjectMetadata(command().object_key, 5, "video/mp4"))
    result = ValidateUploadedVideoUseCase(storage, FakeProcessor(metadata(width=8_000))).execute(
        command()
    )

    assert result.status == "REJECTED"
    assert result.failure_code == "RESOLUTION_NOT_SUPPORTED"


def test_validation_rejects_audio_stream_without_codec() -> None:
    storage = FakeStorage(ObjectMetadata(command().object_key, 5, "video/mp4"))
    result = ValidateUploadedVideoUseCase(
        storage,
        FakeProcessor(metadata(audio_codec=None)),
    ).execute(command())

    assert result.status == "REJECTED"
    assert result.failure_code == "AUDIO_CODEC_INVALID"
