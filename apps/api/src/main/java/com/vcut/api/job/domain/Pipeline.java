package com.vcut.api.job.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Pipeline(
    UUID id,
    UUID userId,
    UUID projectId,
    UUID videoId,
    int version,
    PipelineStatus status,
    UUID correlationId,
    Instant createdAt,
    Instant updatedAt) {

  public Pipeline {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(projectId, "projectId");
    Objects.requireNonNull(videoId, "videoId");
    if (version < 1) {
      throw new IllegalArgumentException("version must be positive");
    }
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  public static Pipeline queued(
      UUID id,
      UUID userId,
      UUID projectId,
      UUID videoId,
      int version,
      UUID correlationId,
      Instant now) {
    return new Pipeline(
        id, userId, projectId, videoId, version, PipelineStatus.QUEUED, correlationId, now, now);
  }
}
