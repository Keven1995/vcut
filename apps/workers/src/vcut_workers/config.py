import os

from pydantic import BaseModel, ConfigDict, Field


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
    rabbitmq_command_queue: str = Field(
        default="vcut.pipeline.commands.video-validation", min_length=1
    )
    rabbitmq_command_exchange: str = Field(default="vcut.pipeline.commands", min_length=1)
    rabbitmq_retry_exchange: str = Field(default="vcut.pipeline.retry", min_length=1)
    rabbitmq_dead_letter_exchange: str = Field(default="vcut.pipeline.dlx", min_length=1)
    rabbitmq_result_exchange: str = Field(default="vcut.pipeline.results", min_length=1)
    rabbitmq_result_routing_key: str = Field(
        default="pipeline.video.validation.completed", min_length=1
    )
    rabbitmq_enabled: bool = True
    storage_endpoint: str = Field(default="http://localhost:9000", min_length=1)
    storage_region: str = Field(default="us-east-1", min_length=1)
    storage_bucket: str = Field(default="vcut-local", min_length=1)
    storage_access_key: str = Field(default="", min_length=0)
    storage_secret_key: str = Field(default="", min_length=0)
    storage_path_style: bool = True
    storage_health_url: str = Field(default="http://localhost:9000/minio/health/live", min_length=1)

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
        )
