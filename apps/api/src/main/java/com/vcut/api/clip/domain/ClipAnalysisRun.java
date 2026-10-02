package com.vcut.api.clip.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ClipAnalysisRun(
    UUID id,
    UUID videoId,
    UUID userId,
    int pipelineVersion,
    DurationPreference durationPreference,
    BigDecimal customDurationSeconds,
    BigDecimal durationSeconds,
    String language,
    AnalysisRunStatus status,
    String errorCode,
    String errorMessage,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt) {

  public ClipAnalysisRun {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(videoId, "videoId");
    Objects.requireNonNull(userId, "userId");
    if (pipelineVersion < 1) {
      throw new IllegalArgumentException("pipelineVersion must be positive");
    }
    Objects.requireNonNull(durationPreference, "durationPreference");
    Objects.requireNonNull(status, "status");
    requireText(language, "language");
    validateDuration(customDurationSeconds, "customDurationSeconds");
    validateDuration(durationSeconds, "durationSeconds");
    if (durationPreference == DurationPreference.CUSTOM) {
      if (customDurationSeconds == null) {
        throw new IllegalArgumentException("customDurationSeconds is required for CUSTOM");
      }
    } else if (customDurationSeconds != null) {
      throw new IllegalArgumentException(
          "customDurationSeconds is only valid for CUSTOM duration preference");
    }
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  public static ClipAnalysisRun queued(
      UUID id,
      UUID videoId,
      UUID userId,
      int pipelineVersion,
      DurationPreference durationPreference,
      BigDecimal customDurationSeconds,
      BigDecimal durationSeconds,
      String language,
      Instant now) {
    return new ClipAnalysisRun(
        id,
        videoId,
        userId,
        pipelineVersion,
        durationPreference,
        customDurationSeconds,
        durationSeconds,
        language,
        AnalysisRunStatus.QUEUED,
        null,
        null,
        now,
        now,
        null);
  }

  private static void validateDuration(BigDecimal value, String field) {
    if (value != null && (value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(90)) > 0)) {
      throw new IllegalArgumentException(field + " must be greater than 0 and at most 90");
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
