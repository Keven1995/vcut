from collections.abc import Callable
from pathlib import Path
from tempfile import TemporaryDirectory

from vcut_workers.application.clip_generation import ClipGenerationLimits, GenerateClipUseCase
from vcut_workers.application.errors import MediaProcessingError
from vcut_workers.application.ports import (
    MediaProcessor,
    RetentionRecorder,
    SmartCropAnalyzer,
    WritableObjectStorage,
)
from vcut_workers.contracts.clip_generation import ClipGenerationCommand
from vcut_workers.contracts.final_render import FinalRenderCommand, FinalRenderResult
from vcut_workers.domain.retention import RetainedObjectKind


class FinalRenderUseCase:
    """Render the immutable final artifact and its thumbnail atomically enough for retry."""

    def __init__(
        self,
        object_storage: WritableObjectStorage,
        media_processor: MediaProcessor,
        limits: ClipGenerationLimits | None = None,
        smart_reframing: SmartCropAnalyzer | None = None,
        smart_reframing_enabled: bool = False,
        retention_recorder: RetentionRecorder | None = None,
    ) -> None:
        self._object_storage = object_storage
        self._media_processor = media_processor
        self._clip_generation = GenerateClipUseCase(
            object_storage,
            media_processor,
            limits,
            smart_reframing,
            smart_reframing_enabled,
            retention_recorder,
            RetainedObjectKind.FINAL,
        )
        self._retention_recorder = retention_recorder

    def execute(
        self,
        command: FinalRenderCommand,
        progress: Callable[[float], None] | None = None,
    ) -> FinalRenderResult:
        report = progress or (lambda value: None)
        output_existed = self._object_storage.head(command.output_object_key) is not None
        report(10)
        generation = self._clip_generation.execute(
            ClipGenerationCommand(
                clip_id=command.clip_id,
                user_id=command.user_id,
                project_id=command.project_id,
                video_id=command.video_id,
                pipeline_version=command.pipeline_version,
                edit_version=command.edit_version,
                source_object_key=command.source_object_key,
                output_object_key=command.output_object_key,
                start_seconds=command.start_seconds,
                end_seconds=command.end_seconds,
                aspect_ratio=command.aspect_ratio,
                caption_preset=command.caption_preset,
                caption_style=command.caption_style,
                caption_cues=command.caption_cues,
                crop_settings=command.crop_settings,
            )
        )
        report(82)
        try:
            existing_thumbnail = self._object_storage.head(command.thumbnail_object_key)
            if existing_thumbnail is None:
                report(90)
                self._create_thumbnail(command)
                report(97)
            elif self._retention_recorder is not None:
                self._retention_recorder.register(
                    command.user_id,
                    command.project_id,
                    command.thumbnail_object_key,
                    RetainedObjectKind.THUMBNAIL,
                    existing_thumbnail.content_length,
                )
        except Exception:
            self._delete_output(command.thumbnail_object_key)
            if not output_existed:
                self._delete_output(command.output_object_key)
            raise
        return FinalRenderResult(
            render_id=command.render_id,
            clip_id=command.clip_id,
            edit_version=command.edit_version,
            status=generation.status.value,
            output_object_key=generation.output_object_key,
            thumbnail_object_key=command.thumbnail_object_key,
            duration_seconds=generation.duration_seconds,
            width=generation.width,
            height=generation.height,
            aspect_ratio=generation.aspect_ratio,
        )

    def _create_thumbnail(self, command: FinalRenderCommand) -> None:
        with TemporaryDirectory(prefix="vcut-final-render-thumbnail-") as directory:
            rendered = Path(directory) / "final.mp4"
            thumbnail = Path(directory) / "thumbnail.jpg"
            self._object_storage.download(command.output_object_key, rendered)
            if not rendered.is_file() or rendered.stat().st_size == 0:
                raise MediaProcessingError(
                    "FINAL_OUTPUT_NOT_FOUND", "final render output was not found"
                )
            self._media_processor.thumbnail(
                rendered,
                thumbnail,
                timestamp_seconds=0,
                width=640,
                height=640,
            )
            if not thumbnail.is_file() or thumbnail.stat().st_size == 0:
                raise MediaProcessingError("THUMBNAIL_EMPTY", "final render thumbnail is empty")
            stored = self._object_storage.upload(
                thumbnail, command.thumbnail_object_key, "image/jpeg"
            )
            if self._retention_recorder is not None:
                self._retention_recorder.register(
                    command.user_id,
                    command.project_id,
                    command.thumbnail_object_key,
                    RetainedObjectKind.THUMBNAIL,
                    stored.content_length,
                )

    def _delete_output(self, object_key: str) -> None:
        try:
            self._object_storage.delete(object_key)
        except Exception:
            pass


__all__ = ["FinalRenderUseCase"]
