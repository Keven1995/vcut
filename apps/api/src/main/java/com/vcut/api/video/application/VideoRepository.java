package com.vcut.api.video.application;

import com.vcut.api.video.domain.Video;
import com.vcut.api.video.domain.VideoUploadStatus;
import java.util.Optional;
import java.util.UUID;

public interface VideoRepository {

  Video save(Video video);

  Optional<Video> findByIdForUser(UUID videoId, UUID userId);

  boolean updateIfStatus(Video video, VideoUploadStatus expectedStatus);

  void delete(UUID videoId, UUID userId);
}
