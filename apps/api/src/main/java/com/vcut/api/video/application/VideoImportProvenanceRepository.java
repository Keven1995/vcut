package com.vcut.api.video.application;

import com.vcut.api.video.domain.VideoImportProvenance;
import java.util.Optional;
import java.util.UUID;

public interface VideoImportProvenanceRepository {

  void save(UUID videoId, VideoImportProvenance provenance);

  Optional<VideoImportProvenance> findByVideoId(UUID videoId);
}
