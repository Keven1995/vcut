package com.vcut.api.clip.application;

import com.vcut.api.clip.domain.Clip;
import com.vcut.api.clip.domain.ClipStatus;
import com.vcut.api.clip.domain.ClipVersion;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ClipRepository {

  Clip save(Clip clip);

  ClipVersion saveVersion(ClipVersion version);

  void update(Clip clip);

  boolean claimGeneration(UUID clipId, UUID userId, int editVersion, Instant now);

  Optional<ClipAggregate> findById(UUID clipId);

  Optional<ClipAggregate> findByIdForUser(UUID clipId, UUID userId);

  ClipPage findByVideoForUser(UUID videoId, UUID userId, int page, int size, ClipStatus status);
}
