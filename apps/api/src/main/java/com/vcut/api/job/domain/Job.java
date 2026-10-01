package com.vcut.api.job.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Job(
    UUID id,
    UUID pipelineId,
    UUID userId,
    UUID projectId,
    UUID videoId,
    String operation,
    int version,
    String idempotencyKey,
    JobStatus status,
    String currentStage,
    int attempt,
    double progress,
    UUID correlationId,
    String errorCode,
    String errorMessage,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt) {

  public Job {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(pipelineId, "pipelineId");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(projectId, "projectId");
    Objects.requireNonNull(videoId, "videoId");
    requireText(operation, "operation");
    if (version < 1 || attempt < 1) {
      throw new IllegalArgumentException("version and attempt must be positive");
    }
    requireText(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(status, "status");
    requireText(currentStage, "currentStage");
    if (progress < 0 || progress > 100) {
      throw new IllegalArgumentException("progress must be between 0 and 100");
    }
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  public static Job queued(
      UUID id,
      Pipeline pipeline,
      String operation,
      String idempotencyKey,
      String stage,
      Instant now) {
    return new Job(
        id,
        pipeline.id(),
        pipeline.userId(),
        pipeline.projectId(),
        pipeline.videoId(),
        operation,
        pipeline.version(),
        idempotencyKey,
        JobStatus.QUEUED,
        stage,
        1,
        0,
        pipeline.correlationId(),
        null,
        null,
        now,
        now,
        null);
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
