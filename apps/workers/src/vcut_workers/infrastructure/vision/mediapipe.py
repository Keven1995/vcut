from collections.abc import Callable, Iterable, Sequence
from dataclasses import dataclass
from importlib import import_module
from pathlib import Path
from typing import Protocol, cast

from vcut_workers.domain.vision import BoundingBox, FaceDetection


class VisionProviderUnavailableError(RuntimeError):
    """Raised when the optional MediaPipe/OpenCV runtime is not installed."""


class _Capture(Protocol):
    def set(self, property_id: int, value: float) -> bool: ...

    def read(self) -> tuple[bool, object]: ...

    def release(self) -> None: ...


class _FaceProcessor(Protocol):
    def process(self, image: object) -> object: ...

    def __enter__(self) -> "_FaceProcessor": ...

    def __exit__(self, type_: object, value: object, traceback: object) -> None: ...


@dataclass(frozen=True)
class _Cv2Bindings:
    position_milliseconds: int
    convert_color: Callable[[object, int], object]
    bgr_to_rgb: int


class MediaPipeFaceDetector:
    """Optional face adapter; raw frames are processed and immediately discarded."""

    def __init__(self, *, min_detection_confidence: float = 0.5) -> None:
        if not 0 < min_detection_confidence <= 1:
            raise ValueError("min_detection_confidence must be between zero and one")
        self._min_detection_confidence = min_detection_confidence

    def detect(
        self, source: Path, *, timestamps_seconds: tuple[float, ...]
    ) -> tuple[FaceDetection, ...]:
        cv2, capture = self._open_capture(source)
        try:
            factory = self._face_processor_factory()
            with factory(
                model_selection=0,
                min_detection_confidence=self._min_detection_confidence,
            ) as processor:
                detections: list[FaceDetection] = []
                for timestamp in timestamps_seconds:
                    capture.set(cv2.position_milliseconds, timestamp * 1_000)
                    success, frame = capture.read()
                    if not success:
                        continue
                    rgb = cv2.convert_color(frame, cv2.bgr_to_rgb)
                    result = processor.process(rgb)
                    detections.extend(_detections_from_result(result, timestamp))
                return tuple(detections)
        finally:
            capture.release()

    def _open_capture(self, source: Path) -> tuple[_Cv2Bindings, _Capture]:
        try:
            cv2_module = import_module("cv2")
        except ImportError as error:
            raise VisionProviderUnavailableError("OpenCV is not installed") from error
        factory = cast(Callable[[str], _Capture], _dynamic_attr(cv2_module, "VideoCapture"))
        capture = factory(str(source))
        if not capture:
            raise VisionProviderUnavailableError("OpenCV could not open the source")
        return (
            _Cv2Bindings(
                position_milliseconds=cast(
                    int, _dynamic_attr(cv2_module, "CAP_PROP_POS_MSEC")
                ),
                convert_color=cast(Callable[[object, int], object], _dynamic_attr(cv2_module, "cvtColor")),
                bgr_to_rgb=cast(int, _dynamic_attr(cv2_module, "COLOR_BGR2RGB")),
            ),
            capture,
        )

    def _face_processor_factory(self) -> Callable[..., _FaceProcessor]:
        try:
            mediapipe = import_module("mediapipe")
            solutions = _dynamic_attr(mediapipe, "solutions")
            face_detection = _dynamic_attr(solutions, "face_detection")
            return cast(Callable[..., _FaceProcessor], _dynamic_attr(face_detection, "FaceDetection"))
        except ImportError as error:
            raise VisionProviderUnavailableError("MediaPipe is not installed") from error


def _detections_from_result(result: object, timestamp: float) -> tuple[FaceDetection, ...]:
    raw_detections = _dynamic_attr(result, "detections")
    if not isinstance(raw_detections, Iterable):
        return ()
    detections: list[FaceDetection] = []
    for raw_detection in cast(Iterable[object], raw_detections):
        location = _dynamic_attr_or_none(raw_detection, "location_data")
        box = _dynamic_attr_or_none(location, "relative_bounding_box")
        if box is None:
            continue
        values = _bounded_box(box)
        if values is None:
            continue
        raw_scores = _dynamic_attr_or_default(raw_detection, "score", ())
        confidence = _first_float(raw_scores, default=0)
        if confidence <= 0:
            continue
        detections.append(
            FaceDetection(
                timestamp_seconds=timestamp,
                bounding_box=values,
                confidence=confidence,
            )
        )
    return tuple(detections)


def _bounded_box(box: object) -> BoundingBox | None:
    try:
        x = float(cast(float, _dynamic_attr(box, "xmin")))
        y = float(cast(float, _dynamic_attr(box, "ymin")))
        width = float(cast(float, _dynamic_attr(box, "width")))
        height = float(cast(float, _dynamic_attr(box, "height")))
    except (TypeError, ValueError, AttributeError):
        return None
    right = min(1.0, max(0.0, x + width))
    bottom = min(1.0, max(0.0, y + height))
    left = min(1.0, max(0.0, x))
    top = min(1.0, max(0.0, y))
    bounded_width = right - left
    bounded_height = bottom - top
    if bounded_width <= 0 or bounded_height <= 0:
        return None
    return BoundingBox(left, top, bounded_width, bounded_height)


def _first_float(values: object, *, default: float) -> float:
    if not isinstance(values, Sequence) or not values:
        return default
    try:
        return max(0.0, min(1.0, float(values[0])))
    except (TypeError, ValueError):
        return default


def _dynamic_attr(value: object, name: str) -> object:
    return getattr(value, name)


def _dynamic_attr_or_none(value: object, name: str) -> object | None:
    try:
        return _dynamic_attr(value, name)
    except AttributeError:
        return None


def _dynamic_attr_or_default(value: object, name: str, default: object) -> object:
    try:
        return _dynamic_attr(value, name)
    except AttributeError:
        return default


__all__ = ["MediaPipeFaceDetector", "VisionProviderUnavailableError"]
