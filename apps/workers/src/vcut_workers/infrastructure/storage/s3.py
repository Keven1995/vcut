from pathlib import Path
from typing import Protocol, cast

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

from vcut_workers.config import WorkerSettings
from vcut_workers.domain.media import ObjectMetadata


class S3Client(Protocol):
    def head_object(self, *, Bucket: str, Key: str) -> dict[str, object]: ...

    def download_file(self, bucket: str, key: str, filename: str) -> None: ...

    def upload_file(
        self,
        filename: str,
        bucket: str,
        key: str,
        *,
        ExtraArgs: dict[str, str],
    ) -> None: ...


class S3ObjectStorage:
    def __init__(self, settings: WorkerSettings) -> None:
        if not settings.storage_access_key or not settings.storage_secret_key:
            raise ValueError("OBJECT_STORAGE_ACCESS_KEY and OBJECT_STORAGE_SECRET_KEY are required")
        self._bucket = settings.storage_bucket
        self._client = cast(
            S3Client,
            boto3.client(
                "s3",
                endpoint_url=settings.storage_endpoint,
                region_name=settings.storage_region,
                aws_access_key_id=settings.storage_access_key,
                aws_secret_access_key=settings.storage_secret_key,
                config=Config(
                    signature_version="s3v4",
                    s3={"addressing_style": "path" if settings.storage_path_style else "auto"},
                ),
            ),
        )

    def exists(self, object_key: str) -> bool:
        return self.head(object_key) is not None

    def head(self, object_key: str) -> ObjectMetadata | None:
        try:
            response = self._client.head_object(Bucket=self._bucket, Key=object_key)
        except ClientError as exception:
            if _is_not_found(exception):
                return None
            raise
        return ObjectMetadata(
            object_key=object_key,
            content_length=int(cast(int, response.get("ContentLength", 0))),
            content_type=_optional_string(response.get("ContentType")),
            e_tag=_optional_string(response.get("ETag")),
            checksum_sha256=_optional_string(response.get("ChecksumSHA256")),
        )

    def download(self, object_key: str, destination: Path) -> None:
        self._client.download_file(self._bucket, object_key, str(destination))

    def upload(self, source: Path, object_key: str, content_type: str) -> ObjectMetadata:
        self._client.upload_file(
            str(source),
            self._bucket,
            object_key,
            ExtraArgs={"ContentType": content_type},
        )
        metadata = self.head(object_key)
        if metadata is None:
            raise RuntimeError("uploaded object was not available after upload")
        return metadata


def _is_not_found(exception: ClientError) -> bool:
    error = exception.response.get("Error", {})
    return error.get("Code") in {"404", "NoSuchKey", "NotFound"}


def _optional_string(value: object) -> str | None:
    return value if isinstance(value, str) else None
