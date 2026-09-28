class InMemoryObjectStorage:
    def __init__(self) -> None:
        self._objects: set[str] = set()

    def put(self, object_key: str) -> None:
        if not object_key:
            raise ValueError("object_key must not be empty")
        self._objects.add(object_key)

    def exists(self, object_key: str) -> bool:
        return object_key in self._objects
