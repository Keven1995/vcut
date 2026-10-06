from collections.abc import Iterable, Sequence
from pathlib import Path
from typing import Protocol
from uuid import UUID

from vcut_workers.application.ports import SceneIntervalStore
from vcut_workers.domain.clip_generation import CropSettings
from vcut_workers.domain.media import VideoAsset
from vcut_workers.domain.vision import (
    BoundingBox,
    FaceDetection,
    RegionKind,
    SceneInterval,
    VisualAnalysis,
)


class SceneDetector(Protocol):
    def detect(
        self,
        source: Path,
        *,
        start_seconds: float,
        end_seconds: float,
        duration_seconds: float,
    ) -> tuple[SceneInterval, ...]:
        """Detect ordered scene intervals inside one clip interval."""


class FaceDetector(Protocol):
    def detect(
        self, source: Path, *, timestamps_seconds: tuple[float, ...]
    ) -> tuple[FaceDetection, ...]:
        """Detect faces without emitting or retaining raw frame contents."""


class TemporalFaceTracker:
    """Smooth a face track so frame-level detector noise does not move the crop."""

    def __init__(self, smoothing: float = 0.35) -> None:
        if not 0 < smoothing <= 1:
            raise ValueError("smoothing must be between zero and one")
        self._smoothing = smoothing

    def track(self, detections: Sequence[FaceDetection]) -> tuple[FaceDetection, ...]:
        previous: dict[int, tuple[float, float]] = {}
        tracked: list[FaceDetection] = []
        for index, detection in enumerate(detections):
            track_id = detection.track_id if detection.track_id is not None else index
            current = (detection.bounding_box.center_x, detection.bounding_box.center_y)
            prior = previous.get(track_id)
            if prior is None:
                smoothed = current
            else:
                smoothed_x = (self._smoothing * current[0]) + ((1 - self._smoothing) * prior[0])
                smoothed_y = (self._smoothing * current[1]) + ((1 - self._smoothing) * prior[1])
                smoothed = (smoothed_x, smoothed_y)
            previous[track_id] = smoothed
            tracked.append(
                FaceDetection(
                    timestamp_seconds=detection.timestamp_seconds,
                    bounding_box=_box_around(
                        smoothed[0],
                        smoothed[1],
                        detection.bounding_box.width,
                        detection.bounding_box.height,
                    ),
                    confidence=detection.confidence,
                    track_id=track_id,
                    speaking_confidence=detection.speaking_confidence,
                )
            )
        return tuple(tracked)


class RelevantRegionSelector:
    """Select speaker, face, motion and event regions in that priority order."""

    def select(
        self,
        analysis: VisualAnalysis,
        *,
        start_seconds: float,
        end_seconds: float,
        fallback: CropSettings,
    ) -> CropSettings:
        faces = [
            face
            for face in analysis.faces
            if start_seconds <= face.timestamp_seconds <= end_seconds
        ]
        speaking_regions = [
            region
            for region in analysis.regions
            if region.kind is RegionKind.SPEAKER
            and start_seconds <= region.timestamp_seconds <= end_seconds
        ]
        if speaking_regions:
            return _crop_from_points(
                (
                    (region.center_x, region.center_y, region.confidence)
                    for region in speaking_regions
                ),
                fallback,
            )
        if faces:
            return _crop_from_points(
                (
                    (
                        face.bounding_box.center_x,
                        face.bounding_box.center_y,
                        face.confidence * (1 + face.speaking_confidence),
                    )
                    for face in faces
                ),
                fallback,
            )
        other_regions = [
            region
            for region in analysis.regions
            if region.kind in (RegionKind.MOTION, RegionKind.EVENT, RegionKind.INTEREST)
            and start_seconds <= region.timestamp_seconds <= end_seconds
        ]
        return _crop_from_points(
            ((region.center_x, region.center_y, region.confidence) for region in other_regions),
            fallback,
        )


class SmartReframingAnalyzer:
    """Provider-neutral visual analysis used by both preview and final render."""

    def __init__(
        self,
        scene_detector: SceneDetector,
        face_detector: FaceDetector,
        *,
        frame_interval_seconds: float = 1.0,
        max_frames: int = 180,
        tracker: TemporalFaceTracker | None = None,
        selector: RelevantRegionSelector | None = None,
        scene_store: SceneIntervalStore | None = None,
    ) -> None:
        if frame_interval_seconds <= 0 or max_frames <= 0:
            raise ValueError("vision sampling limits must be positive")
        self._scene_detector = scene_detector
        self._face_detector = face_detector
        self._frame_interval_seconds = frame_interval_seconds
        self._max_frames = max_frames
        self._tracker = tracker or TemporalFaceTracker()
        self._selector = selector or RelevantRegionSelector()
        self._scene_store = scene_store

    def crop_for(
        self,
        source: Path,
        *,
        video: VideoAsset,
        video_id: UUID,
        pipeline_version: int,
        start_seconds: float,
        end_seconds: float,
        fallback_x: float,
        fallback_y: float,
        fallback_zoom: float,
    ) -> tuple[float, float, float]:
        fallback = CropSettings(x=fallback_x, y=fallback_y, zoom=fallback_zoom)
        if start_seconds < 0 or end_seconds <= start_seconds:
            raise ValueError("smart crop interval is invalid")
        timestamps = _sample_timestamps(
            start_seconds,
            min(end_seconds, video.duration_seconds),
            self._frame_interval_seconds,
            self._max_frames,
        )
        scenes = tuple(
            self._scene_detector.detect(
                source,
                start_seconds=start_seconds,
                end_seconds=end_seconds,
                duration_seconds=video.duration_seconds,
            )
        )
        if self._scene_store is not None:
            try:
                self._scene_store.save(video_id, pipeline_version, scenes)
            except Exception:
                # Scene persistence is useful metadata, but never blocks a render.
                pass
        faces = self._tracker.track(
            self._face_detector.detect(source, timestamps_seconds=timestamps)
        )
        analysis = VisualAnalysis(
            duration_seconds=video.duration_seconds,
            scenes=scenes,
            faces=faces,
        )
        selected = self._selector.select(
            analysis,
            start_seconds=start_seconds,
            end_seconds=end_seconds,
            fallback=fallback,
        )
        return selected.x, selected.y, selected.zoom


def _sample_timestamps(
    start_seconds: float,
    end_seconds: float,
    interval_seconds: float,
    max_frames: int,
) -> tuple[float, ...]:
    if end_seconds <= start_seconds:
        return ()
    values: list[float] = []
    timestamp = start_seconds
    while timestamp < end_seconds and len(values) < max_frames:
        values.append(timestamp)
        timestamp += interval_seconds
    if not values or values[-1] != end_seconds:
        values.append(end_seconds)
    return tuple(values[:max_frames])


def _box_around(center_x: float, center_y: float, width: float, height: float) -> BoundingBox:
    bounded_width = min(width, 1.0)
    bounded_height = min(height, 1.0)
    return BoundingBox(
        x=max(0.0, min(1.0 - bounded_width, center_x - bounded_width / 2)),
        y=max(0.0, min(1.0 - bounded_height, center_y - bounded_height / 2)),
        width=bounded_width,
        height=bounded_height,
    )


def _crop_from_points(
    points: Iterable[tuple[float, float, float]], fallback: CropSettings
) -> CropSettings:
    values = tuple(points)
    if not values:
        return fallback
    total_weight = sum(max(weight, 0.001) for _, _, weight in values)
    center_x = sum(x * max(weight, 0.001) for x, _, weight in values) / total_weight
    center_y = sum(y * max(weight, 0.001) for _, y, weight in values) / total_weight
    return CropSettings(
        x=max(0.0, min(1.0, center_x)),
        y=max(0.0, min(1.0, center_y)),
        zoom=fallback.zoom,
    )


__all__ = [
    "FaceDetector",
    "RelevantRegionSelector",
    "SceneDetector",
    "SmartReframingAnalyzer",
    "TemporalFaceTracker",
]
