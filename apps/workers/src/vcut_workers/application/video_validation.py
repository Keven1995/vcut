from dataclasses import dataclass, field
from pathlib import Path
from tempfile import TemporaryDirectory

from pydantic import BaseModel, Field

from vcut_workers.application.errors import MediaProcessingError
from vcut_workers.application.ports import ObjectStorage, VideoProcessor
from vcut_workers.contracts.video_validation import ValidateVideoCommand, VideoValidationResult
from vcut_workers.domain.media import VideoMetadata


class MediaValidationLimits(BaseModel):
    max_file_size_bytes: int = Field(gt=0, default=2_147_483_648)
    max_duration_seconds: int = Field(gt=0, default=7_200)
    max_width: int = Field(gt=0, default=7_680)
    max_height: int = Field(gt=0, default=4_320)
    accepted_containers: tuple[str, ...] = ("mp4", "mov", "webm")
    accepted_video_codecs: tuple[str, ...] = ("h264", "hevc", "vp9", "av1")


@dataclass(frozen=True)
class ValidateUploadedVideoUseCase:
    object_storage: ObjectStorage
    video_processor: VideoProcessor
    limits: MediaValidationLimits = field(default_factory=MediaValidationLimits)

    def execute(self, command: ValidateVideoCommand) -> VideoValidationResult:
        object_metadata = self.object_storage.head(command.object_key)
        if object_metadata is None:
            return self._rejected(command, 0, "OBJECT_NOT_FOUND")
        if object_metadata.content_length != command.declared_size_bytes:
            return self._rejected(command, object_metadata.content_length, "SIZE_MISMATCH")
        if object_metadata.content_length > self.limits.max_file_size_bytes:
            return self._rejected(command, object_metadata.content_length, "SIZE_LIMIT_EXCEEDED")

        with TemporaryDirectory(prefix="vcut-video-") as directory:
            source = Path(directory) / command.original_filename
            self.object_storage.download(command.object_key, source)
            try:
                metadata = self.video_processor.probe(source)
            except (OSError, ValueError, MediaProcessingError):
                return self._rejected(command, object_metadata.content_length, "INVALID_MEDIA")

        failure_code = _validate_metadata(command, metadata, self.limits)
        if failure_code is not None:
            return self._rejected(command, object_metadata.content_length, failure_code, metadata)
        return VideoValidationResult(
            video_id=command.video_id,
            object_key=command.object_key,
            original_filename=command.original_filename,
            status="READY",
            actual_size_bytes=object_metadata.content_length,
            duration_seconds=metadata.duration_seconds,
            width=metadata.width,
            height=metadata.height,
            frame_rate=metadata.frame_rate,
            has_audio=metadata.has_audio,
            video_codec=metadata.video_codec,
            audio_codec=metadata.audio_codec,
        )

    @staticmethod
    def _rejected(
        command: ValidateVideoCommand,
        actual_size_bytes: int,
        failure_code: str,
        metadata: VideoMetadata | None = None,
    ) -> VideoValidationResult:
        return VideoValidationResult(
            video_id=command.video_id,
            object_key=command.object_key,
            original_filename=command.original_filename,
            status="REJECTED",
            failure_code=failure_code,
            actual_size_bytes=actual_size_bytes,
            duration_seconds=None if metadata is None else metadata.duration_seconds,
            width=None if metadata is None else metadata.width,
            height=None if metadata is None else metadata.height,
            frame_rate=None if metadata is None else metadata.frame_rate,
            has_audio=None if metadata is None else metadata.has_audio,
            video_codec=None if metadata is None else metadata.video_codec,
            audio_codec=None if metadata is None else metadata.audio_codec,
        )


def _validate_metadata(
    command: ValidateVideoCommand,
    metadata: VideoMetadata,
    limits: MediaValidationLimits,
) -> str | None:
    extension = command.original_filename.rsplit(".", 1)[-1].lower()
    if extension not in limits.accepted_containers:
        return "CONTAINER_NOT_SUPPORTED"
    if metadata.container not in limits.accepted_containers:
        return "CONTAINER_NOT_SUPPORTED"
    if metadata.video_codec.lower() not in limits.accepted_video_codecs:
        return "VIDEO_CODEC_NOT_SUPPORTED"
    if (
        metadata.width <= 0
        or metadata.height <= 0
        or metadata.width > limits.max_width
        or metadata.height > limits.max_height
    ):
        return "RESOLUTION_NOT_SUPPORTED"
    if metadata.duration_seconds <= 0:
        return "INVALID_DURATION"
    if metadata.duration_seconds > limits.max_duration_seconds:
        return "DURATION_LIMIT_EXCEEDED"
    if metadata.has_audio and not metadata.audio_codec:
        return "AUDIO_CODEC_INVALID"
    if not _content_type_matches(command.declared_content_type, metadata.container):
        return "CONTENT_TYPE_MISMATCH"
    return None


def _content_type_matches(content_type: str, container: str) -> bool:
    normalized = content_type.lower()
    if container == "mp4":
        return normalized == "video/mp4"
    if container == "mov":
        return normalized == "video/quicktime"
    if container == "webm":
        return normalized == "video/webm"
    return False
