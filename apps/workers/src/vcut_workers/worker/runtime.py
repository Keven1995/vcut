from dataclasses import dataclass

from vcut_workers.application.ports import ObjectStorage, VideoProcessor, VisionAnalyzer


@dataclass(frozen=True)
class WorkerRuntime:
    video_processor: VideoProcessor
    object_storage: ObjectStorage
    vision_analyzer: VisionAnalyzer
