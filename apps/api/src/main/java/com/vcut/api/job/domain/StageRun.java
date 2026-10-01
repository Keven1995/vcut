package com.vcut.api.job.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record StageRun(
    UUID id,
    UUID jobId,
    String stageName,
    StageRunStatus status,
    int attempt,
    double progress,
    String inputPayload,
    String outputPayload,
    String errorCode,
    String errorMessage,
    Instant startedAt,
    Instant finishedAt,
    Instant createdAt,
    Instant updatedAt) {

  public StageRun {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(jobId, "jobId");
    if (stageName == null || stageName.isBlank()) {
      throw new IllegalArgumentException("stageName must not be blank");
    }
    Objects.requireNonNull(status, "status");
    if (attempt < 1 || progress < 0 || progress > 100) {
      throw new IllegalArgumentException("invalid stage run attempt or progress");
    }
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  public static StageRun queued(
      UUID id, UUID jobId, String stageName, String inputPayload, Instant now) {
    return new StageRun(
        id,
        jobId,
        stageName,
        StageRunStatus.QUEUED,
        1,
        0,
        inputPayload,
        null,
        null,
        null,
        null,
        null,
        now,
        now);
  }
}
