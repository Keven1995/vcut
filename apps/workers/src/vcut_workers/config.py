import os

from pydantic import BaseModel, ConfigDict, Field


class WorkerSettings(BaseModel):
    model_config = ConfigDict(frozen=True)

    environment: str = Field(default="local", min_length=1)
    worker_name: str = Field(default="vcut-worker", min_length=1)
    ffmpeg_binary: str = Field(default="ffmpeg", min_length=1)
    health_port: int = Field(default=8090, ge=1, le=65535)
    postgres_host: str = Field(default="localhost", min_length=1)
    postgres_port: int = Field(default=5432, ge=1, le=65535)
    rabbitmq_host: str = Field(default="localhost", min_length=1)
    rabbitmq_port: int = Field(default=5672, ge=1, le=65535)
    storage_endpoint: str = Field(default="http://localhost:9000", min_length=1)
    storage_region: str = Field(default="us-east-1", min_length=1)
    storage_bucket: str = Field(default="vcut-local", min_length=1)
    storage_access_key: str = Field(default="", min_length=0)
    storage_secret_key: str = Field(default="", min_length=0)
    storage_path_style: bool = True
    storage_health_url: str = Field(
        default="http://localhost:9000/minio/health/live", min_length=1
    )

    @classmethod
    def from_environment(cls) -> "WorkerSettings":
        return cls(
            environment=os.getenv("APP_ENV", "local"),
            worker_name=os.getenv("WORKER_NAME", "vcut-worker"),
            ffmpeg_binary=os.getenv("FFMPEG_BINARY", "ffmpeg"),
            health_port=int(os.getenv("WORKER_HEALTH_PORT", "8090")),
            postgres_host=os.getenv("POSTGRES_HOST", "localhost"),
            postgres_port=int(os.getenv("POSTGRES_PORT", "5432")),
            rabbitmq_host=os.getenv("RABBITMQ_HOST", "localhost"),
            rabbitmq_port=int(os.getenv("RABBITMQ_PORT", "5672")),
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
