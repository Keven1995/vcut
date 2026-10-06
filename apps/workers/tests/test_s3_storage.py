from pathlib import Path

from pytest import MonkeyPatch

from vcut_workers.config import WorkerSettings
from vcut_workers.infrastructure.storage.s3 import S3ObjectStorage


class TrackingS3Client:
    def __init__(self) -> None:
        self.object_sizes: dict[tuple[str, str], int] = {}
        self.upload_call: tuple[str, str, str, dict[str, str]] | None = None
        self.download_call: tuple[str, str, str] | None = None

    def head_object(self, *, Bucket: str, Key: str) -> dict[str, object]:
        return {
            "ContentLength": self.object_sizes[(Bucket, Key)],
            "ContentType": "video/mp4",
            "ETag": "test-etag",
        }

    def upload_file(
        self,
        filename: str,
        bucket: str,
        key: str,
        *,
        ExtraArgs: dict[str, str],
    ) -> None:
        self.upload_call = (filename, bucket, key, ExtraArgs)
        self.object_sizes[(bucket, key)] = Path(filename).stat().st_size

    def download_file(self, bucket: str, key: str, filename: str) -> None:
        self.download_call = (bucket, key, filename)
        Path(filename).write_bytes(b"streamed-object")


def test_s3_object_storage_uses_file_transfer_methods(
    monkeypatch: MonkeyPatch, tmp_path: Path
) -> None:
    client = TrackingS3Client()
    monkeypatch.setattr(
        "vcut_workers.infrastructure.storage.s3.boto3.client", lambda *_args, **_kwargs: client
    )
    storage = S3ObjectStorage(
        WorkerSettings(
            storage_bucket="vcut-test",
            storage_access_key="test-access-key",
            storage_secret_key="test-secret-key",
        )
    )
    source = tmp_path / "source.mp4"
    source.write_bytes(b"synthetic-video-content")

    stored = storage.upload(source, "users/test/source/video.mp4", "video/mp4")
    destination = tmp_path / "download.mp4"
    storage.download("users/test/source/video.mp4", destination)

    assert stored.content_length == source.stat().st_size
    assert client.upload_call == (
        str(source),
        "vcut-test",
        "users/test/source/video.mp4",
        {"ContentType": "video/mp4"},
    )
    assert client.download_call == (
        "vcut-test",
        "users/test/source/video.mp4",
        str(destination),
    )
