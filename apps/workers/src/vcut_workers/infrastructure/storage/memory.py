from pathlib import Path

from vcut_workers.domain.media import ObjectMetadata


class InMemoryObjectStorage:
    def __init__(self) -> None:
        self._objects: dict[str, bytes] = {}

    def put(self, object_key: str, content: bytes = b"") -> None:
        if not object_key:
            raise ValueError("object_key must not be empty")
        self._objects[object_key] = content

    def exists(self, object_key: str) -> bool:
        return object_key in self._objects

    def head(self, object_key: str) -> ObjectMetadata | None:
        content = self._objects.get(object_key)
        if content is None:
            return None
        return ObjectMetadata(object_key=object_key, content_length=len(content))

    def download(self, object_key: str, destination: Path) -> None:
        content = self._objects.get(object_key)
        if content is None:
            raise FileNotFoundError(object_key)
        destination.write_bytes(content)
