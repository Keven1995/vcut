from pathlib import Path

from vcut_workers.domain.media import ObjectMetadata


class InMemoryObjectStorage:
    def __init__(self) -> None:
        self._objects: dict[str, bytes] = {}
        self._content_types: dict[str, str | None] = {}

    def put(self, object_key: str, content: bytes = b"", content_type: str | None = None) -> None:
        if not object_key:
            raise ValueError("object_key must not be empty")
        self._objects[object_key] = content
        self._content_types[object_key] = content_type

    def exists(self, object_key: str) -> bool:
        return object_key in self._objects

    def head(self, object_key: str) -> ObjectMetadata | None:
        content = self._objects.get(object_key)
        if content is None:
            return None
        return ObjectMetadata(
            object_key=object_key,
            content_length=len(content),
            content_type=self._content_types.get(object_key),
        )

    def download(self, object_key: str, destination: Path) -> None:
        content = self._objects.get(object_key)
        if content is None:
            raise FileNotFoundError(object_key)
        destination.write_bytes(content)

    def upload(self, source: Path, object_key: str, content_type: str) -> ObjectMetadata:
        self.put(object_key, source.read_bytes(), content_type)
        metadata = self.head(object_key)
        if metadata is None:
            raise RuntimeError("uploaded object was not available after upload")
        return metadata
