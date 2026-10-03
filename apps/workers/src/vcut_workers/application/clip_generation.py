import logging
from math import isfinite
from pathlib import Path
from tempfile import TemporaryDirectory

from pydantic import BaseModel, ConfigDict, Field

from vcut_workers.application.errors import MediaProcessingError
from vcut_workers.application.ports import MediaProcessor, SmartCropAnalyzer, WritableObjectStorage
from vcut_workers.contracts.clip_generation import (
    ClipGenerationCommand,
    ClipGenerationResult,
)
from vcut_workers.domain.clip_generation import (
    AspectRatio,
    CaptionTrack,
    ClipComposition,
    ClipStatus,
    CropSettings,
)
from vcut_workers.domain.media import VideoAsset, VideoMetadata

LOGGER = logging.getLogger(__name__)


class ClipGenerationLimits(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    max_input_size_bytes: int = Field(default=536_870_912, gt=0)
    max_duration_seconds: float = Field(default=90, gt=0)
    duration_tolerance_seconds: float = Field(default=0.1, ge=0)
    max_caption_cues: int = Field(default=500, ge=0)
    vertical_width: int = Field(default=1080, gt=0)
    vertical_height: int = Field(default=1920, gt=0)
    horizontal_width: int = Field(default=1920, gt=0)
    horizontal_height: int = Field(default=1080, gt=0)


class GenerateClipUseCase:
    """Generate one idempotent clip from a validated command and object keys."""

    def __init__(
        self,
        object_storage: WritableObjectStorage,
        media_processor: MediaProcessor,
        limits: ClipGenerationLimits | None = None,
        smart_reframing: SmartCropAnalyzer | None = None,
        smart_reframing_enabled: bool = False,
    ) -> None:
        self._object_storage = object_storage
        self._media_processor = media_processor
        self._limits = limits or ClipGenerationLimits()
        self._smart_reframing = smart_reframing
        self._smart_reframing_enabled = smart_reframing_enabled

    def execute(self, command: ClipGenerationCommand) -> ClipGenerationResult:
        if len(command.caption_cues) > self._limits.max_caption_cues:
            raise MediaProcessingError(
                "CAPTION_CUE_LIMIT_EXCEEDED", "clip contains too many caption cues"
            )
        if command.duration_seconds > self._limits.max_duration_seconds:
            raise MediaProcessingError(
                "CLIP_DURATION_LIMIT_EXCEEDED", "clip duration exceeds the configured limit"
            )

        if self._object_storage.head(command.output_object_key) is not None:
            return self._reuse_existing(command)

        try:
            with TemporaryDirectory(prefix="vcut-clip-generation-") as directory:
                source = Path(directory) / "source"
                destination = Path(directory) / "clip.mp4"
                source_metadata = self._object_storage.head(command.source_object_key)
                if source_metadata is None:
                    raise MediaProcessingError("SOURCE_NOT_FOUND", "source media was not found")
                if source_metadata.content_length > self._limits.max_input_size_bytes:
                    raise MediaProcessingError(
                        "INPUT_SIZE_LIMIT_EXCEEDED",
                        "source media exceeds the configured size limit",
                    )
                self._object_storage.download(command.source_object_key, source)
                if not source.is_file() or source.stat().st_size == 0:
                    raise MediaProcessingError(
                        "SOURCE_DOWNLOAD_EMPTY", "downloaded source media is empty"
                    )

                source_video_metadata = self._media_processor.probe(source)
                self._validate_source_interval(command, source_video_metadata)
                crop_settings = self._crop_settings(command, source, source_video_metadata)
                composition = ClipComposition(
                    start_seconds=command.start_seconds,
                    end_seconds=command.end_seconds,
                    aspect_ratio=command.aspect_ratio,
                    caption_track=CaptionTrack(
                        cues=command.caption_cues,
                        duration_seconds=command.duration_seconds,
                    ),
                    caption_style=command.caption_style,
                    crop_settings=crop_settings,
                    width=self._width_for(command.aspect_ratio),
                    height=self._height_for(command.aspect_ratio),
                )
                self._media_processor.compose(source, destination, composition)
                final_metadata = self._media_processor.probe(destination)
                self._validate_final_metadata(command, final_metadata)
                self._object_storage.upload(
                    destination,
                    command.output_object_key,
                    "video/mp4",
                )
                return _ready_result(command, final_metadata)
        except Exception:
            self._delete_output(command.output_object_key)
            raise

    def _reuse_existing(self, command: ClipGenerationCommand) -> ClipGenerationResult:
        try:
            with TemporaryDirectory(prefix="vcut-clip-reuse-") as directory:
                destination = Path(directory) / "clip.mp4"
                self._object_storage.download(command.output_object_key, destination)
                metadata = self._media_processor.probe(destination)
                self._validate_final_metadata(command, metadata)
                return _ready_result(command, metadata)
        except Exception:
            self._delete_output(command.output_object_key)
            raise

    def _validate_source_interval(
        self, command: ClipGenerationCommand, metadata: VideoMetadata
    ) -> None:
        if command.end_seconds > metadata.duration_seconds:
            raise MediaProcessingError(
                "CLIP_INTERVAL_OUT_OF_RANGE", "clip interval is outside source media"
            )

    def _crop_settings(
        self, command: ClipGenerationCommand, source: Path, metadata: VideoMetadata
    ) -> CropSettings:
        crop_settings = command.crop_settings
        if (
            command.aspect_ratio is not AspectRatio.VERTICAL
            or not self._smart_reframing_enabled
            or self._smart_reframing is None
            or crop_settings != CropSettings.centered()
        ):
            return crop_settings
        try:
            x, y, zoom = self._smart_reframing.crop_for(
                source,
                video=VideoAsset(
                    object_key=command.source_object_key,
                    duration_seconds=metadata.duration_seconds,
                ),
                video_id=command.video_id,
                pipeline_version=command.pipeline_version,
                start_seconds=command.start_seconds,
                end_seconds=command.end_seconds,
                fallback_x=crop_settings.x,
                fallback_y=crop_settings.y,
                fallback_zoom=crop_settings.zoom,
            )
            return CropSettings(x=x, y=y, zoom=zoom)
        except Exception as error:
            LOGGER.warning("smart_reframing_fallback error=%s", type(error).__name__)
            return crop_settings

    def _validate_final_metadata(
        self, command: ClipGenerationCommand, metadata: VideoMetadata
    ) -> None:
        expected_width = self._width_for(command.aspect_ratio)
        expected_height = self._height_for(command.aspect_ratio)
        if metadata.width != expected_width or metadata.height != expected_height:
            raise MediaProcessingError(
                "CLIP_DIMENSIONS_MISMATCH", "rendered clip dimensions do not match aspect ratio"
            )
        if metadata.sample_aspect_ratio != "1:1":
            raise MediaProcessingError(
                "CLIP_SAMPLE_ASPECT_RATIO_INVALID", "rendered clip sample aspect ratio is not 1:1"
            )
        if not isfinite(metadata.duration_seconds) or abs(
            metadata.duration_seconds - command.duration_seconds
        ) > self._limits.duration_tolerance_seconds:
            raise MediaProcessingError(
                "CLIP_DURATION_MISMATCH", "rendered clip duration does not match the interval"
            )

    def _width_for(self, aspect_ratio: AspectRatio) -> int:
        return (
            self._limits.vertical_width
            if aspect_ratio is AspectRatio.VERTICAL
            else self._limits.horizontal_width
        )

    def _height_for(self, aspect_ratio: AspectRatio) -> int:
        return (
            self._limits.vertical_height
            if aspect_ratio is AspectRatio.VERTICAL
            else self._limits.horizontal_height
        )

    def _delete_output(self, object_key: str) -> None:
        try:
            self._object_storage.delete(object_key)
        except Exception:
            pass


def _ready_result(
    command: ClipGenerationCommand, metadata: VideoMetadata
) -> ClipGenerationResult:
    return ClipGenerationResult(
        clip_id=command.clip_id,
        edit_version=command.edit_version,
        status=ClipStatus.READY,
        output_object_key=command.output_object_key,
        duration_seconds=metadata.duration_seconds,
        width=metadata.width,
        height=metadata.height,
        aspect_ratio=command.aspect_ratio,
    )


__all__ = ["ClipGenerationLimits", "GenerateClipUseCase"]
