from vcut_workers.domain.media import VideoAsset, VisionSignal


class DeterministicVisionAnalyzer:
    def analyze(self, video: VideoAsset) -> tuple[VisionSignal, ...]:
        """Return no signals until a vision provider is configured."""
        return ()
