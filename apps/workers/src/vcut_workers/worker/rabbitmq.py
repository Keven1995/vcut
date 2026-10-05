import asyncio
import logging
import time
from collections.abc import Callable
from datetime import UTC, datetime
from typing import Generic, TypeVar, cast
from uuid import uuid4

import pika
from pydantic import BaseModel, ValidationError

from vcut_workers.application.clip_analysis import GenerateClipCandidatesUseCase
from vcut_workers.application.clip_generation import ClipGenerationLimits, GenerateClipUseCase
from vcut_workers.application.final_render import FinalRenderUseCase
from vcut_workers.application.media_processing import (
    ExtractAudioUseCase,
    MediaPipelineCommand,
    MediaProcessingLimits,
)
from vcut_workers.application.ports import MultimodalAnalyzer, SmartCropAnalyzer
from vcut_workers.application.smart_reframing import SmartReframingAnalyzer
from vcut_workers.application.transcription import TranscribeAudioUseCase, TranscriptionCommand
from vcut_workers.application.video_validation import ValidateUploadedVideoUseCase
from vcut_workers.config import WorkerSettings
from vcut_workers.contracts.clip_analysis import AnalyzeClipsCommand, AnalyzeClipsResult
from vcut_workers.contracts.clip_generation import ClipGenerationCommand, ClipGenerationResult
from vcut_workers.contracts.final_render import FinalRenderCommand, FinalRenderResult
from vcut_workers.contracts.messaging import (
    MessageEnvelope,
    MessageKind,
    RetryMetadata,
    StageRunUpdate,
)
from vcut_workers.contracts.transcription import TranscribeAudioCommand
from vcut_workers.contracts.video_validation import ValidateVideoCommand, VideoValidationResult
from vcut_workers.domain.clip_generation import ClipStatus
from vcut_workers.domain.transcription import TranscriptionResult
from vcut_workers.infrastructure.analysis.deterministic import (
    DeterministicContentAnalyzer,
    FallbackContentAnalyzer,
)
from vcut_workers.infrastructure.analysis.multimodal import (
    DeterministicMultimodalAnalyzer,
    ResilientMultimodalAnalyzer,
    TranscriptFallbackMultimodalAnalyzer,
)
from vcut_workers.infrastructure.ffmpeg.processor import (
    FFmpegExecutionLimits,
    FFmpegVideoProcessor,
)
from vcut_workers.infrastructure.persistence.idempotency import PostgresIdempotencyStore
from vcut_workers.infrastructure.persistence.job_eligibility import PostgresJobEligibilityStore
from vcut_workers.infrastructure.persistence.retention import PostgresRetentionStore
from vcut_workers.infrastructure.persistence.transcription import (
    ObjectStorageTranscriptionResultStore,
)
from vcut_workers.infrastructure.persistence.vision import PostgresSceneIntervalStore
from vcut_workers.infrastructure.storage.s3 import S3ObjectStorage
from vcut_workers.infrastructure.transcription.fake import DeterministicTranscriptionProvider
from vcut_workers.infrastructure.transcription.whisper import WhisperTranscriptionProvider
from vcut_workers.infrastructure.vision.deterministic import DeterministicFaceDetector
from vcut_workers.infrastructure.vision.mediapipe import MediaPipeFaceDetector
from vcut_workers.infrastructure.vision.scene import FFmpegSceneDetector
from vcut_workers.observability_tracing import current_traceparent
from vcut_workers.worker.consumer import (
    ConsumerBase,
    MessageDelivery,
    ProgressHandler,
    ResultPublisher,
    RetryPublisher,
)
from vcut_workers.worker.errors import ProcessingErrorInfo
from vcut_workers.worker.metrics import (
    WORKER_IN_PROGRESS,
    record_worker_delivery,
)
from vcut_workers.worker.retry import RetryPolicy

LOGGER = logging.getLogger(__name__)
CommandModelT = TypeVar("CommandModelT", bound=BaseModel)
ResultModelT = TypeVar("ResultModelT", bound=BaseModel)


class PikaDelivery(MessageDelivery):
    def __init__(
        self, channel: pika.adapters.blocking_connection.BlockingChannel, body: bytes, tag: int
    ) -> None:
        self._channel = channel
        self._body = body
        self._tag = tag

    @property
    def body(self) -> bytes:
        return self._body

    @property
    def delivery_tag(self) -> str:
        return str(self._tag)

    async def ack(self) -> None:
        self._channel.basic_ack(delivery_tag=self._tag)

    async def reject(self, *, requeue: bool) -> None:
        self._channel.basic_nack(delivery_tag=self._tag, requeue=requeue)


class PikaPublisher(RetryPublisher, ResultPublisher):
    def __init__(
        self,
        channel: pika.adapters.blocking_connection.BlockingChannel,
        settings: WorkerSettings,
        *,
        command_routing_key: str = "pipeline.video.validate",
        result_routing_key: str | None = None,
        result_event_type: str = "VideoValidationCompleted",
    ) -> None:
        self._channel = channel
        self._settings = settings
        self._command_routing_key = command_routing_key
        self._result_routing_key = result_routing_key or settings.rabbitmq_result_routing_key
        self._result_event_type = result_event_type

    async def publish_retry(self, body: bytes, metadata: RetryMetadata) -> None:
        self._channel.basic_publish(
            exchange=self._settings.rabbitmq_retry_exchange,
            routing_key=self._command_routing_key,
            body=body,
            properties=pika.BasicProperties(
                content_type="application/json",
                delivery_mode=2,
                expiration=str(max(1, int(metadata.backoff_seconds * 1000))),
                priority=self._worker_priority(body),
            ),
        )

    async def publish_dead_letter(self, body: bytes, error: ProcessingErrorInfo) -> None:
        self._channel.basic_publish(
            exchange=self._settings.rabbitmq_dead_letter_exchange,
            routing_key=self._command_routing_key,
            body=body,
            properties=pika.BasicProperties(
                content_type="application/json",
                delivery_mode=2,
                headers={
                    "x-error-code": error.code,
                    "x-error-message": error.message,
                    "x-error-classification": error.classification.value,
                },
                priority=self._worker_priority(body),
            ),
        )
        try:
            envelope = MessageEnvelope.model_validate_json(body)
        except ValidationError:
            return
        if self._result_event_type == "VideoValidationCompleted":
            failure_body = _result_envelope(
                envelope,
                {
                    "status": "REJECTED",
                    "failureCode": error.code,
                    "actualSizeBytes": 0,
                },
                self._result_event_type,
            )
            self._publish_result(failure_body)
        elif self._result_event_type == "ClipAnalysisCompleted":
            failure_body = _result_envelope(
                envelope,
                {
                    "status": "FAILED",
                    "failureCode": error.code,
                    "candidates": [],
                    "hasReliableCandidate": False,
                },
                self._result_event_type,
            )
            self._publish_result(failure_body)
        elif self._result_event_type == "ClipGenerationCompleted":
            try:
                command = ClipGenerationCommand.model_validate(envelope.data)
            except ValidationError:
                return
            failure = ClipGenerationResult(
                clip_id=command.clip_id,
                edit_version=command.edit_version,
                status=ClipStatus.FAILED,
                duration_seconds=0,
                width=0,
                height=0,
                aspect_ratio=command.aspect_ratio,
                error_code=error.code,
                error_message=error.message,
            )
            failure_body = _result_envelope(
                envelope,
                cast(
                    dict[str, object],
                    failure.model_dump(mode="json", by_alias=True, exclude_none=True),
                ),
                self._result_event_type,
            )
            self._publish_result(failure_body)
        elif self._result_event_type == "FinalRenderCompleted":
            try:
                final_command = FinalRenderCommand.model_validate(envelope.data)
            except ValidationError:
                return
            final_failure = FinalRenderResult(
                render_id=final_command.render_id,
                clip_id=final_command.clip_id,
                edit_version=final_command.edit_version,
                status="FAILED",
                duration_seconds=0,
                width=0,
                height=0,
                aspect_ratio=final_command.aspect_ratio,
                error_code=error.code,
                error_message=error.message,
            )
            failure_body = _result_envelope(
                envelope,
                cast(
                    dict[str, object],
                    final_failure.model_dump(mode="json", by_alias=True, exclude_none=True),
                ),
                self._result_event_type,
            )
            self._publish_result(failure_body)

    async def publish_result(self, envelope: MessageEnvelope, result: BaseModel) -> None:
        data = cast(
            dict[str, object], result.model_dump(mode="json", by_alias=True, exclude_none=True)
        )
        self._publish_result(_result_envelope(envelope, data, self._result_event_type))

    async def update(self, update: StageRunUpdate) -> None:
        data: dict[str, object] = {
            "status": update.status.value,
            "attempt": update.attempt,
        }
        if update.progress is not None:
            data["progress"] = update.progress
        if update.error_code is not None:
            data["errorCode"] = update.error_code
        if update.error_message is not None:
            data["errorMessage"] = update.error_message
        self._publish_result(_stage_update_envelope(update, data))

    def _publish_result(self, body: bytes) -> None:
        self._channel.basic_publish(
            exchange=self._settings.rabbitmq_result_exchange,
            routing_key=self._result_routing_key,
            body=body,
            properties=pika.BasicProperties(content_type="application/json", delivery_mode=2),
        )

    def _worker_priority(self, body: bytes) -> int:
        try:
            envelope = MessageEnvelope.model_validate_json(body)
        except ValidationError:
            return 0
        priority = envelope.data.get("workerPriority", 0)
        if isinstance(priority, bool) or not isinstance(priority, int):
            return 0
        if priority < 0 or priority > self._settings.rabbitmq_max_priority:
            return 0
        return priority


class RabbitMqWorker(Generic[CommandModelT, ResultModelT]):
    def __init__(
        self,
        settings: WorkerSettings,
        command_type: type[CommandModelT],
        result_type: type[ResultModelT],
        handler: Callable[[CommandModelT], ResultModelT],
        *,
        command_queue: str,
        command_routing_key: str,
        result_routing_key: str,
        result_event_type: str,
        progress_handler: ProgressHandler[CommandModelT, ResultModelT] | None = None,
    ) -> None:
        self._settings = settings
        self._command_type = command_type
        self._result_type = result_type
        self._handler = handler
        self._command_queue = command_queue
        self._command_routing_key = command_routing_key
        self._result_routing_key = result_routing_key
        self._result_event_type = result_event_type
        self._progress_handler = progress_handler

    def run_forever(self) -> None:
        credentials = pika.PlainCredentials(
            self._settings.rabbitmq_username, self._settings.rabbitmq_password
        )
        parameters = pika.ConnectionParameters(
            host=self._settings.rabbitmq_host,
            port=self._settings.rabbitmq_port,
            virtual_host=self._settings.rabbitmq_virtual_host,
            credentials=credentials,
            heartbeat=60,
        )
        while True:
            try:
                self._consume_once(parameters)
            except KeyboardInterrupt:
                raise
            except Exception as exception:
                LOGGER.warning("worker_connection_failed error=%s", exception)
                time.sleep(5)

    def _consume_once(self, parameters: pika.ConnectionParameters) -> None:
        connection = pika.BlockingConnection(parameters)
        channel = connection.channel()
        channel.confirm_delivery()
        channel.basic_qos(prefetch_count=1)
        channel.queue_declare(
            queue=self._command_queue,
            durable=True,
            arguments={
                "x-dead-letter-exchange": self._settings.rabbitmq_dead_letter_exchange,
                "x-dead-letter-routing-key": self._command_routing_key,
                "x-max-priority": self._settings.rabbitmq_max_priority,
            },
        )
        publisher = PikaPublisher(
            channel,
            self._settings,
            command_routing_key=self._command_routing_key,
            result_routing_key=self._result_routing_key,
            result_event_type=self._result_event_type,
        )
        consumer = ConsumerBase(
            self._command_type,
            self._result_type,
            self._handler,
            PostgresIdempotencyStore(self._settings),
            retry_policy=RetryPolicy(
                max_attempts=3, initial_backoff_seconds=5, backoff_multiplier=6
            ),
            retry_publisher=publisher,
            result_publisher=publisher,
            stage_run_updater=publisher,
            progress_handler=self._progress_handler,
            eligibility_store=PostgresJobEligibilityStore(self._settings),
            queue_name=self._command_queue,
        )

        def on_message(
            current_channel: pika.adapters.blocking_connection.BlockingChannel,
            method: pika.spec.Basic.Deliver,
            properties: pika.BasicProperties,
            body: bytes,
        ) -> None:
            del properties
            delivery = PikaDelivery(current_channel, body, method.delivery_tag)
            started_at = time.perf_counter()
            WORKER_IN_PROGRESS.labels(queue=self._command_queue).inc()
            try:
                outcome = asyncio.run(consumer.consume(delivery))
                record_worker_delivery(
                    self._command_queue,
                    outcome.status.value,
                    time.perf_counter() - started_at,
                )
            except Exception as exception:
                record_worker_delivery(
                    self._command_queue,
                    "DELIVERY_ERROR",
                    time.perf_counter() - started_at,
                )
                LOGGER.exception("worker_delivery_failed error=%s", exception)
                try:
                    current_channel.basic_nack(delivery_tag=method.delivery_tag, requeue=True)
                except pika.exceptions.AMQPError:
                    LOGGER.exception("worker_delivery_requeue_failed")
            finally:
                WORKER_IN_PROGRESS.labels(queue=self._command_queue).dec()

        channel.basic_consume(
            queue=self._command_queue,
            on_message_callback=on_message,
            auto_ack=False,
        )
        channel.start_consuming()


def create_video_validation_worker(
    settings: WorkerSettings,
) -> RabbitMqWorker[ValidateVideoCommand, VideoValidationResult]:
    storage = S3ObjectStorage(settings)
    processor = FFmpegVideoProcessor(
        settings.ffmpeg_binary,
        execution_limits=FFmpegExecutionLimits(
            timeout_seconds=settings.ffmpeg_timeout_seconds,
            max_temp_bytes=settings.ffmpeg_max_temp_bytes,
            max_memory_bytes=settings.ffmpeg_max_memory_bytes,
        ),
    )
    use_case = ValidateUploadedVideoUseCase(storage, processor)
    return RabbitMqWorker(
        settings,
        ValidateVideoCommand,
        VideoValidationResult,
        use_case.execute,
        command_queue=settings.rabbitmq_command_queue,
        command_routing_key="pipeline.video.validate",
        result_routing_key=settings.rabbitmq_result_routing_key,
        result_event_type="VideoValidationCompleted",
    )


def create_transcription_worker(
    settings: WorkerSettings,
) -> RabbitMqWorker[TranscribeAudioCommand, TranscriptionResult]:
    storage = S3ObjectStorage(settings)
    media_processor = FFmpegVideoProcessor(
        settings.ffmpeg_binary,
        execution_limits=FFmpegExecutionLimits(
            timeout_seconds=settings.ffmpeg_timeout_seconds,
            max_temp_bytes=settings.ffmpeg_max_temp_bytes,
            max_memory_bytes=settings.ffmpeg_max_memory_bytes,
        ),
    )
    audio_preparation = ExtractAudioUseCase(
        storage,
        media_processor,
        MediaProcessingLimits(
            max_input_size_bytes=settings.clip_max_input_size_bytes,
            max_processing_seconds=settings.ffmpeg_timeout_seconds,
            max_temp_bytes=settings.ffmpeg_max_temp_bytes,
            max_memory_bytes=settings.ffmpeg_max_memory_bytes,
        ),
        retention_recorder=PostgresRetentionStore(settings),
    )
    provider = (
        DeterministicTranscriptionProvider()
        if settings.transcription_provider == "fake"
        else WhisperTranscriptionProvider.from_settings(settings)
    )
    use_case = TranscribeAudioUseCase(
        provider,
        ObjectStorageTranscriptionResultStore(storage),
        storage,
        provider_name=settings.transcription_provider,
    )

    def transcribe(command: TranscribeAudioCommand) -> TranscriptionResult:
        if command.source_object_key is not None:
            if command.user_id is None or command.project_id is None:
                raise ValueError("source audio preparation requires user and project identifiers")
            audio_preparation.execute(
                MediaPipelineCommand(
                    user_id=command.user_id,
                    project_id=command.project_id,
                    video_id=command.video_id,
                    pipeline_version=command.pipeline_version,
                    source_object_key=command.source_object_key,
                )
            )
        return use_case.execute(
            TranscriptionCommand(
                video_id=command.video_id,
                pipeline_version=command.pipeline_version,
                audio_object_key=command.audio_object_key,
                language=command.language,
            )
        )

    return RabbitMqWorker(
        settings,
        TranscribeAudioCommand,
        TranscriptionResult,
        transcribe,
        command_queue=settings.rabbitmq_transcription_queue,
        command_routing_key="pipeline.video.transcribe",
        result_routing_key=settings.rabbitmq_transcription_result_routing_key,
        result_event_type="TranscriptionCompleted",
    )


def create_clip_analysis_worker(
    settings: WorkerSettings,
) -> RabbitMqWorker[AnalyzeClipsCommand, AnalyzeClipsResult]:
    analyzer = (
        DeterministicContentAnalyzer()
        if settings.content_analysis_provider == "deterministic"
        else FallbackContentAnalyzer()
    )
    object_storage = S3ObjectStorage(settings) if settings.multimodal_analysis_enabled else None
    multimodal_analyzer = _multimodal_analyzer_for(settings)
    use_case = GenerateClipCandidatesUseCase(
        analyzer,
        multimodal_analyzer=multimodal_analyzer,
        object_storage=object_storage,
    )
    return RabbitMqWorker(
        settings,
        AnalyzeClipsCommand,
        AnalyzeClipsResult,
        use_case.execute,
        command_queue=settings.rabbitmq_clip_analysis_queue,
        command_routing_key="pipeline.video.analyze-clips",
        result_routing_key=settings.rabbitmq_clip_analysis_result_routing_key,
        result_event_type="ClipAnalysisCompleted",
    )


def create_clip_generation_worker(
    settings: WorkerSettings,
) -> RabbitMqWorker[ClipGenerationCommand, ClipGenerationResult]:
    storage = S3ObjectStorage(settings)
    processor = FFmpegVideoProcessor(
        settings.ffmpeg_binary,
        execution_limits=FFmpegExecutionLimits(
            timeout_seconds=settings.ffmpeg_timeout_seconds,
            max_temp_bytes=settings.ffmpeg_max_temp_bytes,
            max_memory_bytes=settings.ffmpeg_max_memory_bytes,
        ),
    )
    smart_reframing = _smart_reframing_for(settings)
    use_case = GenerateClipUseCase(
        storage,
        processor,
        ClipGenerationLimits(
            max_input_size_bytes=settings.clip_max_input_size_bytes,
            max_duration_seconds=settings.clip_max_duration_seconds,
            duration_tolerance_seconds=settings.clip_duration_tolerance_seconds,
            max_caption_cues=settings.clip_max_caption_cues,
            vertical_width=settings.clip_vertical_width,
            vertical_height=settings.clip_vertical_height,
            horizontal_width=settings.clip_horizontal_width,
            horizontal_height=settings.clip_horizontal_height,
        ),
        smart_reframing,
        settings.smart_reframing_enabled,
        retention_recorder=PostgresRetentionStore(settings),
    )
    return RabbitMqWorker(
        settings,
        ClipGenerationCommand,
        ClipGenerationResult,
        use_case.execute,
        command_queue=settings.rabbitmq_clip_generation_queue,
        command_routing_key="pipeline.video.generate-clip",
        result_routing_key=settings.rabbitmq_clip_generation_result_routing_key,
        result_event_type="ClipGenerationCompleted",
    )


def create_final_render_worker(
    settings: WorkerSettings,
) -> RabbitMqWorker[FinalRenderCommand, FinalRenderResult]:
    storage = S3ObjectStorage(settings)
    processor = FFmpegVideoProcessor(
        settings.ffmpeg_binary,
        execution_limits=FFmpegExecutionLimits(
            timeout_seconds=settings.ffmpeg_timeout_seconds,
            max_temp_bytes=settings.ffmpeg_max_temp_bytes,
            max_memory_bytes=settings.ffmpeg_max_memory_bytes,
        ),
    )
    smart_reframing = _smart_reframing_for(settings)
    use_case = FinalRenderUseCase(
        storage,
        processor,
        ClipGenerationLimits(
            max_input_size_bytes=settings.clip_max_input_size_bytes,
            max_duration_seconds=settings.clip_max_duration_seconds,
            duration_tolerance_seconds=settings.clip_duration_tolerance_seconds,
            max_caption_cues=settings.clip_max_caption_cues,
            vertical_width=settings.clip_vertical_width,
            vertical_height=settings.clip_vertical_height,
            horizontal_width=settings.clip_horizontal_width,
            horizontal_height=settings.clip_horizontal_height,
        ),
        smart_reframing,
        settings.smart_reframing_enabled,
        retention_recorder=PostgresRetentionStore(settings),
    )
    return RabbitMqWorker(
        settings,
        FinalRenderCommand,
        FinalRenderResult,
        use_case.execute,
        command_queue=settings.rabbitmq_final_render_queue,
        command_routing_key="pipeline.video.final-render",
        result_routing_key=settings.rabbitmq_final_render_result_routing_key,
        result_event_type="FinalRenderCompleted",
        progress_handler=use_case.execute,
    )


def _smart_reframing_for(settings: WorkerSettings) -> SmartCropAnalyzer | None:
    if not settings.smart_reframing_enabled:
        return None
    face_detector = (
        MediaPipeFaceDetector(min_detection_confidence=settings.vision_face_confidence)
        if settings.face_tracking_enabled and settings.vision_provider == "mediapipe"
        else DeterministicFaceDetector()
    )
    return SmartReframingAnalyzer(
        FFmpegSceneDetector(
            settings.ffmpeg_binary,
            threshold=settings.vision_scene_threshold,
            timeout_seconds=settings.ffmpeg_timeout_seconds,
        ),
        face_detector,
        frame_interval_seconds=settings.vision_frame_interval_seconds,
        max_frames=settings.vision_max_frames,
        scene_store=PostgresSceneIntervalStore(settings),
    )


def _multimodal_analyzer_for(settings: WorkerSettings) -> MultimodalAnalyzer | None:
    if not settings.multimodal_analysis_enabled:
        return None
    if settings.multimodal_analysis_provider == "transcript-fallback":
        return TranscriptFallbackMultimodalAnalyzer()
    primary = DeterministicMultimodalAnalyzer(
        FFmpegSceneDetector(
            settings.ffmpeg_binary,
            threshold=settings.vision_scene_threshold,
            timeout_seconds=settings.ffmpeg_timeout_seconds,
        )
    )
    return ResilientMultimodalAnalyzer(primary, TranscriptFallbackMultimodalAnalyzer())


def _result_envelope(envelope: MessageEnvelope, data: dict[str, object], event_type: str) -> bytes:
    result = MessageEnvelope.model_validate(
        {
            "kind": MessageKind.EVENT,
            "eventId": uuid4(),
            "eventType": event_type,
            "eventVersion": 1,
            "jobId": envelope.job_id,
            "resourceId": envelope.resource_id,
            "operation": envelope.operation,
            "version": envelope.version,
            "correlationId": envelope.correlation_id,
            "traceparent": current_traceparent() or envelope.traceparent,
            "attempt": envelope.attempt,
            "occurredAt": datetime.now(UTC),
            "data": data,
        }
    )
    return result.model_dump_json(by_alias=True).encode("utf-8")


def _stage_update_envelope(update: StageRunUpdate, data: dict[str, object]) -> bytes:
    result = MessageEnvelope.model_validate(
        {
            "kind": MessageKind.EVENT,
            "eventId": uuid4(),
            "eventType": "StageRunUpdated",
            "eventVersion": 1,
            "jobId": update.job_id,
            "resourceId": update.resource_id,
            "operation": update.operation,
            "version": update.version,
            "correlationId": update.correlation_id,
            "traceparent": current_traceparent(),
            "attempt": update.attempt,
            "occurredAt": datetime.now(UTC),
            "data": data,
        }
    )
    return result.model_dump_json(by_alias=True).encode("utf-8")
