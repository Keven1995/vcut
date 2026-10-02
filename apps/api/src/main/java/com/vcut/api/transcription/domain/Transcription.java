package com.vcut.api.transcription.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Transcription(
    UUID id,
    UUID videoId,
    UUID userId,
    int pipelineVersion,
    String provider,
    String language,
    String text,
    BigDecimal durationSeconds,
    BigDecimal confidence,
    TranscriptionStatus status,
    List<TranscriptionSegment> segments,
    String errorCode,
    String errorMessage,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt) {

  public Transcription {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(videoId, "videoId");
    Objects.requireNonNull(userId, "userId");
    if (pipelineVersion < 1) {
      throw new IllegalArgumentException("pipelineVersion must be positive");
    }
    requireText(provider, "provider");
    requireText(language, "language");
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    Objects.requireNonNull(durationSeconds, "durationSeconds");
    if (durationSeconds.signum() < 0) {
      throw new IllegalArgumentException("durationSeconds must not be negative");
    }
    if (confidence != null
        && (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0)) {
      throw new IllegalArgumentException("confidence must be between 0 and 1");
    }
    Objects.requireNonNull(status, "status");
    segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  public static Transcription queued(
      UUID id,
      UUID videoId,
      UUID userId,
      int pipelineVersion,
      String provider,
      String language,
      Instant now) {
    return new Transcription(
        id,
        videoId,
        userId,
        pipelineVersion,
        provider,
        language,
        "",
        BigDecimal.ZERO,
        null,
        TranscriptionStatus.QUEUED,
        List.of(),
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
