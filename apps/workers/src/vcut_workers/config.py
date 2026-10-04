import os
from typing import Literal, cast

from pydantic import BaseModel, ConfigDict, Field


def _default_free_retention() -> dict[str, int]:
    return {
        "ORIGINAL": 30,
        "NORMALIZED": 30,
        "AUDIO": 7,
        "FRAMES": 3,
        "PREVIEW": 7,
        "FINAL": 30,
        "THUMBNAIL": 30,
        "FAILED_JOB_ARTIFACT": 7,
    }


def _default_pro_retention() -> dict[str, int]:
    return {
        "ORIGINAL": 90,
        "NORMALIZED": 90,
        "AUDIO": 30,
        "FRAMES": 14,
        "PREVIEW": 30,
        "FINAL": 90,
        "THUMBNAIL": 90,
        "FAILED_JOB_ARTIFACT": 14,
    }


def _retention_from_environment(prefix: str, defaults: dict[str, int]) -> dict[str, int]:
    return {
        asset_type: int(os.getenv(f"{prefix}_{asset_type}_DAYS", str(default_days)))
        for asset_type, default_days in defaults.items()
    }


class WorkerSettings(BaseModel):
    model_config = ConfigDict(frozen=True)

    environment: str = Field(default="local", min_length=1)
    worker_name: str = Field(default="vcut-worker", min_length=1)
    ffmpeg_binary: str = Field(default="ffmpeg", min_length=1)
    ffmpeg_timeout_seconds: float = Field(default=900, gt=0)
    ffmpeg_max_temp_bytes: int = Field(default=4_294_967_296, gt=0)
    ffmpeg_max_memory_bytes: int = Field(default=1_073_741_824, gt=0)
    health_port: int = Field(default=8090, ge=1, le=65535)
    postgres_host: str = Field(default="localhost", min_length=1)
    postgres_port: int = Field(default=5432, ge=1, le=65535)
    postgres_database: str = Field(default="vcut", min_length=1)
    postgres_username: str = Field(default="vcut", min_length=1)
    postgres_password: str = Field(default="", min_length=0)
    rabbitmq_host: str = Field(default="localhost", min_length=1)
    rabbitmq_port: int = Field(default=5672, ge=1, le=65535)
    rabbitmq_username: str = Field(default="vcut", min_length=1)
    rabbitmq_password: str = Field(default="", min_length=0)
    rabbitmq_virtual_host: str = Field(default="/", min_length=1)
    rabbitmq_max_priority: int = Field(default=10, ge=1, le=10)
    rabbitmq_command_queue: str = Field(
        default="vcut.pipeline.commands.video-validation", min_length=1
    )
    rabbitmq_transcription_queue: str = Field(
        default="vcut.pipeline.commands.transcription", min_length=1
    )
    rabbitmq_clip_analysis_queue: str = Field(
        default="vcut.pipeline.commands.clip-analysis", min_length=1
    )
    rabbitmq_clip_generation_queue: str = Field(
        default="vcut.pipeline.commands.clip-generation", min_length=1
    )
    rabbitmq_final_render_queue: str = Field(
        default="vcut.pipeline.commands.final-render", min_length=1
    )
    rabbitmq_command_exchange: str = Field(default="vcut.pipeline.commands", min_length=1)
    rabbitmq_retry_exchange: str = Field(default="vcut.pipeline.retry", min_length=1)
    rabbitmq_dead_letter_exchange: str = Field(default="vcut.pipeline.dlx", min_length=1)
    rabbitmq_result_exchange: str = Field(default="vcut.pipeline.results", min_length=1)
    rabbitmq_result_routing_key: str = Field(
        default="pipeline.video.validation.completed", min_length=1
    )
    rabbitmq_transcription_result_routing_key: str = Field(
        default="pipeline.video.transcription.completed", min_length=1
    )
    rabbitmq_clip_analysis_result_routing_key: str = Field(
        default="pipeline.video.clip-analysis.completed", min_length=1
    )
    rabbitmq_clip_generation_result_routing_key: str = Field(
        default="pipeline.video.clip-generation.completed", min_length=1
    )
    rabbitmq_final_render_result_routing_key: str = Field(
        default="pipeline.video.final-render.completed", min_length=1
    )
    rabbitmq_enabled: bool = True
    storage_endpoint: str = Field(default="http://localhost:9000", min_length=1)
    storage_region: str = Field(default="us-east-1", min_length=1)
    storage_bucket: str = Field(default="vcut-local", min_length=1)
    storage_access_key: str = Field(default="", min_length=0)
    storage_secret_key: str = Field(default="", min_length=0)
    storage_path_style: bool = True
    storage_health_url: str = Field(default="http://localhost:9000/minio/health/live", min_length=1)
    transcription_provider: Literal["fake", "whisper"] = "fake"
    whisper_model_size: str = Field(default="small", min_length=1)
    whisper_device: str = Field(default="cpu", min_length=1)
    whisper_compute_type: str = Field(default="int8", min_length=1)
    whisper_beam_size: int = Field(default=5, ge=1)
    transcription_language: str | None = None
    content_analysis_provider: Literal["deterministic", "fallback"] = "deterministic"
    clip_generation_enabled: bool = True
    clip_max_input_size_bytes: int = Field(default=2_147_483_648, gt=0)
    clip_max_duration_seconds: float = Field(default=90, gt=0)
    clip_duration_tolerance_seconds: float = Field(default=0.1, ge=0)
    clip_max_caption_cues: int = Field(default=500, ge=0)
    clip_vertical_width: int = Field(default=1080, gt=0)
    clip_vertical_height: int = Field(default=1920, gt=0)
    clip_horizontal_width: int = Field(default=1920, gt=0)
    clip_horizontal_height: int = Field(default=1080, gt=0)
    smart_reframing_enabled: bool = False
    vision_provider: Literal["deterministic", "mediapipe"] = "deterministic"
    vision_scene_threshold: float = Field(default=0.4, gt=0, le=1)
    vision_frame_interval_seconds: float = Field(default=1, gt=0)
    vision_max_frames: int = Field(default=180, gt=0)
    vision_face_confidence: float = Field(default=0.5, gt=0, le=1)
    multimodal_analysis_enabled: bool = False
    multimodal_analysis_provider: Literal["deterministic", "transcript-fallback"] = (
        "deterministic"
    )
    face_tracking_enabled: bool = False
    auto_zoom_enabled: bool = False
    retention_cleanup_enabled: bool = False
    retention_cleanup_dry_run: bool = True
    retention_cleanup_interval_seconds: int = Field(default=3600, gt=0)
    retention_cleanup_batch_size: int = Field(default=100, gt=0)
    retention_days_free: dict[str, int] = Field(default_factory=_default_free_retention)
    retention_days_pro: dict[str, int] = Field(default_factory=_default_pro_retention)

    @classmethod
    def from_environment(cls) -> "WorkerSettings":
        return cls(
            environment=os.getenv("APP_ENV", "local"),
            worker_name=os.getenv("WORKER_NAME", "vcut-worker"),
            ffmpeg_binary=os.getenv("FFMPEG_BINARY", "ffmpeg"),
            ffmpeg_timeout_seconds=float(os.getenv("FFMPEG_TIMEOUT_SECONDS", "900")),
            ffmpeg_max_temp_bytes=int(os.getenv("FFMPEG_MAX_TEMP_BYTES", "4294967296")),
            ffmpeg_max_memory_bytes=int(os.getenv("FFMPEG_MAX_MEMORY_BYTES", "1073741824")),
            health_port=int(os.getenv("WORKER_HEALTH_PORT", "8090")),
            postgres_host=os.getenv("POSTGRES_HOST", "localhost"),
            postgres_port=int(os.getenv("POSTGRES_PORT", "5432")),
            postgres_database=os.getenv("POSTGRES_DB", "vcut"),
            postgres_username=os.getenv("POSTGRES_USER", "vcut"),
            postgres_password=os.getenv("POSTGRES_PASSWORD", ""),
            rabbitmq_host=os.getenv("RABBITMQ_HOST", "localhost"),
            rabbitmq_port=int(os.getenv("RABBITMQ_PORT", "5672")),
            rabbitmq_username=os.getenv("RABBITMQ_USER", "vcut"),
            rabbitmq_password=os.getenv("RABBITMQ_PASSWORD", ""),
            rabbitmq_virtual_host=os.getenv("RABBITMQ_VHOST", "/"),
            rabbitmq_max_priority=int(os.getenv("RABBITMQ_MAX_PRIORITY", "10")),
            rabbitmq_transcription_queue=os.getenv(
                "RABBITMQ_TRANSCRIPTION_QUEUE", "vcut.pipeline.commands.transcription"
            ),
            rabbitmq_clip_analysis_queue=os.getenv(
                "RABBITMQ_CLIP_ANALYSIS_QUEUE", "vcut.pipeline.commands.clip-analysis"
            ),
            rabbitmq_clip_generation_queue=os.getenv(
                "RABBITMQ_CLIP_GENERATION_QUEUE", "vcut.pipeline.commands.clip-generation"
            ),
            rabbitmq_final_render_queue=os.getenv(
                "RABBITMQ_FINAL_RENDER_QUEUE", "vcut.pipeline.commands.final-render"
            ),
            rabbitmq_transcription_result_routing_key=os.getenv(
                "RABBITMQ_TRANSCRIPTION_RESULT_ROUTING_KEY",
                "pipeline.video.transcription.completed",
            ),
            rabbitmq_clip_analysis_result_routing_key=os.getenv(
                "RABBITMQ_CLIP_ANALYSIS_RESULT_ROUTING_KEY",
                "pipeline.video.clip-analysis.completed",
            ),
            rabbitmq_clip_generation_result_routing_key=os.getenv(
                "RABBITMQ_CLIP_GENERATION_RESULT_ROUTING_KEY",
                "pipeline.video.clip-generation.completed",
            ),
            rabbitmq_final_render_result_routing_key=os.getenv(
                "RABBITMQ_FINAL_RENDER_RESULT_ROUTING_KEY",
                "pipeline.video.final-render.completed",
            ),
            rabbitmq_enabled=os.getenv("RABBITMQ_ENABLED", "true").lower() == "true",
            storage_endpoint=os.getenv("OBJECT_STORAGE_ENDPOINT", "http://localhost:9000"),
            storage_region=os.getenv("OBJECT_STORAGE_REGION", "us-east-1"),
            storage_bucket=os.getenv("OBJECT_STORAGE_BUCKET", "vcut-local"),
            storage_access_key=os.getenv("OBJECT_STORAGE_ACCESS_KEY", ""),
            storage_secret_key=os.getenv("OBJECT_STORAGE_SECRET_KEY", ""),
            storage_path_style=os.getenv("OBJECT_STORAGE_PATH_STYLE", "true").lower() == "true",
            storage_health_url=os.getenv(
                "OBJECT_STORAGE_HEALTH_URL", "http://localhost:9000/minio/health/live"
            ),
            transcription_provider=cast(
                Literal["fake", "whisper"],
                os.getenv("TRANSCRIPTION_PROVIDER", "fake"),
            ),
            whisper_model_size=os.getenv("WHISPER_MODEL_SIZE", "small"),
            whisper_device=os.getenv("WHISPER_DEVICE", "cpu"),
            whisper_compute_type=os.getenv("WHISPER_COMPUTE_TYPE", "int8"),
            whisper_beam_size=int(os.getenv("WHISPER_BEAM_SIZE", "5")),
            transcription_language=os.getenv("TRANSCRIPTION_LANGUAGE") or None,
            content_analysis_provider=cast(
                Literal["deterministic", "fallback"],
                os.getenv("CONTENT_ANALYSIS_PROVIDER", "deterministic"),
            ),
            clip_generation_enabled=os.getenv("CLIP_GENERATION_ENABLED", "true").lower() == "true",
            clip_max_input_size_bytes=int(
                os.getenv("CLIP_MAX_INPUT_SIZE_BYTES", "2147483648")
            ),
            clip_max_duration_seconds=float(os.getenv("CLIP_MAX_DURATION_SECONDS", "90")),
            clip_duration_tolerance_seconds=float(
                os.getenv("CLIP_DURATION_TOLERANCE_SECONDS", "0.1")
            ),
            clip_max_caption_cues=int(os.getenv("CLIP_MAX_CAPTION_CUES", "500")),
            clip_vertical_width=int(os.getenv("CLIP_VERTICAL_WIDTH", "1080")),
            clip_vertical_height=int(os.getenv("CLIP_VERTICAL_HEIGHT", "1920")),
            clip_horizontal_width=int(os.getenv("CLIP_HORIZONTAL_WIDTH", "1920")),
            clip_horizontal_height=int(os.getenv("CLIP_HORIZONTAL_HEIGHT", "1080")),
            smart_reframing_enabled=os.getenv(
                "AI_SMART_CROP", os.getenv("SMART_REFRAMING_ENABLED", "false")
            ).lower()
            == "true",
            vision_provider=cast(
                Literal["deterministic", "mediapipe"],
                os.getenv("VISION_PROVIDER", "deterministic"),
            ),
            vision_scene_threshold=float(os.getenv("VISION_SCENE_THRESHOLD", "0.4")),
            vision_frame_interval_seconds=float(
                os.getenv("VISION_FRAME_INTERVAL_SECONDS", "1")
            ),
            vision_max_frames=int(os.getenv("VISION_MAX_FRAMES", "180")),
            vision_face_confidence=float(os.getenv("VISION_FACE_CONFIDENCE", "0.5")),
            multimodal_analysis_enabled=os.getenv("MULTIMODAL_ANALYSIS", "false").lower()
            == "true",
            multimodal_analysis_provider=cast(
                Literal["deterministic", "transcript-fallback"],
                os.getenv("MULTIMODAL_ANALYSIS_PROVIDER", "deterministic"),
            ),
            face_tracking_enabled=os.getenv("FACE_TRACKING", "false").lower() == "true",
            auto_zoom_enabled=os.getenv("AUTO_ZOOM", "false").lower() == "true",
            retention_cleanup_enabled=os.getenv("RETENTION_CLEANUP_ENABLED", "false").lower()
            == "true",
            retention_cleanup_dry_run=os.getenv("RETENTION_CLEANUP_DRY_RUN", "true").lower()
            == "true",
            retention_cleanup_interval_seconds=int(
                os.getenv("RETENTION_CLEANUP_INTERVAL_SECONDS", "3600")
            ),
            retention_cleanup_batch_size=int(os.getenv("RETENTION_CLEANUP_BATCH_SIZE", "100")),
            retention_days_free=_retention_from_environment(
                "RETENTION_FREE", _default_free_retention()
            ),
            retention_days_pro=_retention_from_environment(
                "RETENTION_PRO", _default_pro_retention()
            ),
        )
