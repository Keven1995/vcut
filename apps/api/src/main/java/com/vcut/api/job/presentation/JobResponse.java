package com.vcut.api.job.presentation;

import com.vcut.api.job.domain.Job;
import java.time.Instant;
import java.util.UUID;

public record JobResponse(
    UUID id,
    UUID videoId,
    String operation,
    String status,
    String stage,
    int attempt,
    double progress,
    String errorCode,
    String errorMessage,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt) {

  public static JobResponse from(Job job) {
    return new JobResponse(
        job.id(),
        job.videoId(),
        job.operation(),
        job.status().name(),
        job.currentStage(),
        job.attempt(),
        job.progress(),
        job.errorCode(),
        job.errorMessage(),
        job.createdAt(),
        job.updatedAt(),
        job.completedAt());
  }
}
