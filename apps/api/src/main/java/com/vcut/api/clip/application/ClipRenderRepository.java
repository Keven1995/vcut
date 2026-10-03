package com.vcut.api.clip.application;

import com.vcut.api.clip.domain.ClipRender;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClipRenderRepository {

  ClipRender save(ClipRender render);

  void update(ClipRender render);

  Optional<ClipRender> findById(UUID renderId);

  Optional<ClipRender> findByIdForUser(UUID renderId, UUID userId);

  Optional<ClipRender> findByClipAndVersionForUser(UUID clipId, int editVersion, UUID userId);

  List<ClipRender> findByClipForUser(UUID clipId, UUID userId);

  boolean claimRetry(UUID renderId, UUID userId, Instant now);
}
