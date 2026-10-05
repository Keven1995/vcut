from dataclasses import dataclass
from pathlib import Path
from tempfile import TemporaryDirectory
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field

from vcut_workers.application.errors import MediaProcessingError
from vcut_workers.application.ports import MediaProcessor, RetentionRecorder, WritableObjectStorage
from vcut_workers.domain.media import AudioMetadata, VideoMetadata
from vcut_workers.domain.retention import RetainedObjectKind


class MediaProcessingLimits(BaseModel):
    model_config = ConfigDict(frozen=True)

    max_input_size_bytes: int = Field(default=2_147_483_648, gt=0)
    max_duration_seconds: float = Field(default=7_200, gt=0)
    max_temp_bytes: int = Field(default=4_294_967_296, gt=0)
    max_processing_seconds: float = Field(default=900, gt=0)
    max_memory_bytes: int = Field(default=1_073_741_824, gt=0)
    normalization_fps: float = Field(default=30, gt=0)
    normalization_video_codec: str = Field(default="libx264", min_length=1)
    normalization_audio_codec: str = Field(default="aac", min_length=1)
    audio_sample_rate: int = Field(default=16_000, gt=0)
    audio_channels: int = Field(default=1, gt=0)
    max_audio_duration_drift_seconds: float = Field(default=1, ge=0)
    sample_width: int = Field(default=640, gt=0)
    sample_height: int = Field(default=360, gt=0)


@dataclass(frozen=True)
class MediaPipelineCommand:
    user_id: UUID
    project_id: UUID
    video_id: UUID
    pipeline_version: int
    source_object_key: str

    def __post_init__(self) -> None:
        if self.pipeline_version < 1:
            raise ValueError("pipeline_version must be positive")
        if not self.source_object_key or ".." in Path(self.source_object_key).parts:
            raise ValueError("source_object_key must be a safe non-empty path")


class MediaStageResult(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    stage_name: str = Field(min_length=1)
    pipeline_version: int = Field(gt=0)
    input_object_key: str = Field(min_length=1)
    output_object_keys: tuple[str, ...] = Field(min_length=1)
    metadata: dict[str, object] = Field(default_factory=dict)


class NormalizeVideoUseCase:
    def __init__(
        self,
        object_storage: WritableObjectStorage,
        media_processor: MediaProcessor,
        limits: MediaProcessingLimits | None = None,
        retention_recorder: RetentionRecorder | None = None,
    ) -> None:
        self._object_storage = object_storage
        self._media_processor = media_processor
        self._limits = limits or MediaProcessingLimits()
        self._retention_recorder = retention_recorder

    def execute(self, command: MediaPipelineCommand) -> MediaStageResult:
        output_key = artifact_key(command, "normalized", "video.mp4")
        existing = self._object_storage.head(output_key)
        if existing is not None:
            return _result(
                "NORMALIZE",
                command,
                command.source_object_key,
                output_key,
                {"reused": True, "contentLength": existing.content_length},
            )

        with TemporaryDirectory(prefix="vcut-normalize-") as directory:
            source = Path(directory) / "source"
            destination = Path(directory) / "video.mp4"
            self._download_and_validate_source(command, source)
            metadata = self._media_processor.probe(source)
            _validate_video_limits(metadata, self._limits)
            normalized = self._media_processor.normalize(
                source,
                destination,
                target_fps=self._limits.normalization_fps,
                video_codec=self._limits.normalization_video_codec,
                audio_codec=self._limits.normalization_audio_codec,
            )
            _validate_artifact_size(destination, self._limits)
            stored = self._object_storage.upload(destination, output_key, "video/mp4")
            if self._retention_recorder is not None:
                self._retention_recorder.register(
                    command.user_id,
                    command.project_id,
                    output_key,
                    RetainedObjectKind.NORMALIZED,
                    stored.content_length,
                )

        return _result(
            "NORMALIZE",
            command,
            command.source_object_key,
            output_key,
            {
                "reused": False,
                "contentLength": stored.content_length,
                "container": normalized.container,
                "durationSeconds": normalized.duration_seconds,
                "width": normalized.width,
                "height": normalized.height,
                "frameRate": normalized.frame_rate,
                "hasAudio": normalized.has_audio,
                "videoCodec": normalized.video_codec,
                "audioCodec": normalized.audio_codec,
            },
        )

    def _download_and_validate_source(
        self, command: MediaPipelineCommand, destination: Path
    ) -> None:
        source_metadata = self._object_storage.head(command.source_object_key)
        if source_metadata is None:
            raise MediaProcessingError("SOURCE_NOT_FOUND", "source media was not found")
        if source_metadata.content_length > self._limits.max_input_size_bytes:
            raise MediaProcessingError(
                "INPUT_SIZE_LIMIT_EXCEEDED", "source media exceeds the configured size limit"
            )
        self._object_storage.download(command.source_object_key, destination)
        _validate_artifact_size(destination, self._limits)


class ExtractAudioUseCase:
    def __init__(
        self,
        object_storage: WritableObjectStorage,
        media_processor: MediaProcessor,
        limits: MediaProcessingLimits | None = None,
        retention_recorder: RetentionRecorder | None = None,
    ) -> None:
        self._object_storage = object_storage
        self._media_processor = media_processor
        self._limits = limits or MediaProcessingLimits()
        self._retention_recorder = retention_recorder

    def execute(self, command: MediaPipelineCommand) -> MediaStageResult:
        normalized_key = artifact_key(command, "normalized", "video.mp4")
        input_key = (
            normalized_key
            if self._object_storage.head(normalized_key) is not None
            else command.source_object_key
        )
        output_key = artifact_key(command, "audio", "transcription.wav")
        existing = self._object_storage.head(output_key)
        if existing is not None:
            if self._retention_recorder is not None:
                self._retention_recorder.register(
                    command.user_id,
                    command.project_id,
                    output_key,
                    RetainedObjectKind.AUDIO,
                    existing.content_length,
                )
            return _result(
                "EXTRACT_AUDIO",
                command,
                input_key,
                {output_key},
                {"reused": True, "contentLength": existing.content_length},
            )

        with TemporaryDirectory(prefix="vcut-audio-") as directory:
            source = Path(directory) / "video.mp4"
            destination = Path(directory) / "audio.wav"
            self._download_input(input_key, source)
            video_metadata = self._media_processor.probe(source)
            _validate_video_limits(video_metadata, self._limits)
            audio_metadata = self._media_processor.extract_audio(
                source,
                destination,
                sample_rate=self._limits.audio_sample_rate,
                channels=self._limits.audio_channels,
            )
            _validate_audio_duration(video_metadata, audio_metadata, self._limits)
            _validate_artifact_size(destination, self._limits)
            stored = self._object_storage.upload(destination, output_key, "audio/wav")
            if self._retention_recorder is not None:
                self._retention_recorder.register(
                    command.user_id,
                    command.project_id,
                    output_key,
                    RetainedObjectKind.AUDIO,
                    stored.content_length,
                )

        return _result(
            "EXTRACT_AUDIO",
            command,
            input_key,
            {output_key},
            {
                "reused": False,
                "contentLength": stored.content_length,
                "format": audio_metadata.format,
                "durationSeconds": audio_metadata.duration_seconds,
                "sampleRate": audio_metadata.sample_rate,
                "channels": audio_metadata.channels,
                "codec": audio_metadata.codec,
            },
        )

    def _download_input(self, object_key: str, destination: Path) -> None:
        if self._object_storage.head(object_key) is None:
            raise MediaProcessingError("MEDIA_SOURCE_NOT_FOUND", "source media was not found")
        self._object_storage.download(object_key, destination)


class GenerateMediaSamplesUseCase:
    def __init__(
        self,
        object_storage: WritableObjectStorage,
        media_processor: MediaProcessor,
        limits: MediaProcessingLimits | None = None,
        retention_recorder: RetentionRecorder | None = None,
    ) -> None:
        self._object_storage = object_storage
        self._media_processor = media_processor
        self._limits = limits or MediaProcessingLimits()
        self._retention_recorder = retention_recorder

    def execute(
        self,
        command: MediaPipelineCommand,
        timestamps_seconds: tuple[float, ...] = (0.0,),
    ) -> MediaStageResult:
        if not timestamps_seconds:
            raise ValueError("timestamps_seconds must not be empty")
        input_key = artifact_key(command, "normalized", "video.mp4")
        thumbnail_key = artifact_key(command, "thumbnails", "thumbnail.jpg")
        frame_keys = tuple(
            artifact_key(command, "frames", f"frame-{index:04d}.jpg")
            for index in range(len(timestamps_seconds))
        )
        output_keys = (thumbnail_key, *frame_keys)
        if all(self._object_storage.head(key) is not None for key in output_keys):
            return _result("MEDIA_SAMPLES", command, input_key, output_keys, {"reused": True})

        with TemporaryDirectory(prefix="vcut-samples-") as directory:
            source = Path(directory) / "video.mp4"
            thumbnail = Path(directory) / "thumbnail.jpg"
            frames_directory = Path(directory) / "frames"
            self._download_input(input_key, source)
            metadata = self._media_processor.probe(source)
            _validate_video_limits(metadata, self._limits)
            if any(
                timestamp < 0 or timestamp > metadata.duration_seconds
                for timestamp in timestamps_seconds
            ):
                raise MediaProcessingError(
                    "SAMPLE_TIMESTAMP_OUT_OF_RANGE",
                    "sample timestamp is outside the media duration",
                )
            self._media_processor.thumbnail(
                source,
                thumbnail,
                timestamp_seconds=timestamps_seconds[0],
                width=self._limits.sample_width,
                height=self._limits.sample_height,
            )
            frames = self._media_processor.sample_frames(
                source,
                frames_directory,
                timestamps_seconds=timestamps_seconds,
                width=self._limits.sample_width,
                height=self._limits.sample_height,
            )
            _validate_artifact_size(thumbnail, self._limits)
            for frame in frames:
                _validate_artifact_size(frame, self._limits)
            thumbnail_metadata = self._object_storage.upload(thumbnail, thumbnail_key, "image/jpeg")
            if self._retention_recorder is not None:
                self._retention_recorder.register(
                    command.user_id,
                    command.project_id,
                    thumbnail_key,
                    RetainedObjectKind.THUMBNAIL,
                    thumbnail_metadata.content_length,
                )
            for key, frame in zip(frame_keys, frames, strict=True):
                frame_metadata = self._object_storage.upload(frame, key, "image/jpeg")
                if self._retention_recorder is not None:
                    self._retention_recorder.register(
                        command.user_id,
                        command.project_id,
                        key,
                        RetainedObjectKind.FRAMES,
                        frame_metadata.content_length,
                    )

        return _result(
            "MEDIA_SAMPLES",
            command,
            input_key,
            output_keys,
            {"reused": False, "sampleCount": len(frames)},
        )

    def _download_input(self, object_key: str, destination: Path) -> None:
        if self._object_storage.head(object_key) is None:
            raise MediaProcessingError(
                "NORMALIZED_SOURCE_NOT_FOUND", "normalized media was not found"
            )
        self._object_storage.download(object_key, destination)


class PrepareMediaPipeline:
    def __init__(
        self,
        normalize: NormalizeVideoUseCase,
        extract_audio: ExtractAudioUseCase,
        samples: GenerateMediaSamplesUseCase,
    ) -> None:
        self._normalize = normalize
        self._extract_audio = extract_audio
        self._samples = samples

    def execute(
        self,
        command: MediaPipelineCommand,
        timestamps_seconds: tuple[float, ...] = (0.0,),
    ) -> tuple[MediaStageResult, ...]:
        normalized = self._normalize.execute(command)
        audio = self._extract_audio.execute(command)
        samples = self._samples.execute(command, timestamps_seconds)
        return normalized, audio, samples


def artifact_key(command: MediaPipelineCommand, category: str, filename: str) -> str:
    return (
        f"users/{command.user_id}/projects/{command.project_id}/{category}/"
        f"{command.video_id}/v{command.pipeline_version}/{filename}"
    )


def _result(
    stage_name: str,
    command: MediaPipelineCommand,
    input_object_key: str,
    output_object_keys: set[str] | tuple[str, ...] | str,
    metadata: dict[str, object] | None = None,
) -> MediaStageResult:
    keys: tuple[str, ...]
    if isinstance(output_object_keys, str):
        keys = (output_object_keys,)
    elif isinstance(output_object_keys, set):
        keys = tuple(sorted(output_object_keys))
    else:
        keys = output_object_keys
    return MediaStageResult(
        stage_name=stage_name,
        pipeline_version=command.pipeline_version,
        input_object_key=input_object_key,
        output_object_keys=keys,
        metadata=metadata or {},
    )


def _validate_video_limits(metadata: VideoMetadata, limits: MediaProcessingLimits) -> None:
    if metadata.duration_seconds > limits.max_duration_seconds:
        raise MediaProcessingError(
            "DURATION_LIMIT_EXCEEDED", "media duration exceeds the configured limit"
        )


def _validate_audio_duration(
    video: VideoMetadata,
    audio: AudioMetadata,
    limits: MediaProcessingLimits,
) -> None:
    if (
        abs(video.duration_seconds - audio.duration_seconds)
        > limits.max_audio_duration_drift_seconds
    ):
        raise MediaProcessingError(
            "AUDIO_DURATION_MISMATCH", "extracted audio duration is not aligned with the video"
        )


def _validate_artifact_size(path: Path, limits: MediaProcessingLimits) -> None:
    size = path.stat().st_size
    if size > limits.max_temp_bytes:
        raise MediaProcessingError(
            "TEMPORARY_STORAGE_LIMIT_EXCEEDED",
            "media processing artifact exceeds the temporary storage limit",
        )
