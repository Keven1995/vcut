package com.vcut.api.clip.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ClipCandidate(
    UUID id,
    UUID analysisRunId,
    UUID videoId,
    UUID userId,
    CandidateVariant variant,
    CandidateStatus status,
    BigDecimal startSeconds,
    BigDecimal endSeconds,
    String group,
    String title,
    String description,
    String justification,
    BigDecimal hookScore,
    BigDecimal contextScore,
    BigDecimal developmentScore,
    BigDecimal payoffScore,
    BigDecimal independenceScore,
    BigDecimal engagementScore,
    Instant createdAt,
    Instant updatedAt) {

  public ClipCandidate {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(analysisRunId, "analysisRunId");
    Objects.requireNonNull(videoId, "videoId");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(variant, "variant");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(startSeconds, "startSeconds");
    Objects.requireNonNull(endSeconds, "endSeconds");
    if (startSeconds.signum() < 0
        || endSeconds.compareTo(startSeconds) <= 0
        || endSeconds.subtract(startSeconds).compareTo(BigDecimal.valueOf(90)) > 0) {
      throw new IllegalArgumentException("candidate interval must be valid and at most 90 seconds");
    }
    requireText(group, "group");
    requireText(title, "title");
    requireText(description, "description");
    requireText(justification, "justification");
    validateScore(hookScore, "hookScore");
    validateScore(contextScore, "contextScore");
    validateScore(developmentScore, "developmentScore");
    validateScore(payoffScore, "payoffScore");
    validateScore(independenceScore, "independenceScore");
    validateScore(engagementScore, "engagementScore");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  public ClipCandidate withStatus(CandidateStatus newStatus, Instant now) {
    return new ClipCandidate(
        id,
        analysisRunId,
        videoId,
        userId,
        variant,
        newStatus,
        startSeconds,
        endSeconds,
        group,
        title,
        description,
        justification,
        hookScore,
        contextScore,
        developmentScore,
        payoffScore,
        independenceScore,
        engagementScore,
        createdAt,
        now);
  }

  private static void validateScore(BigDecimal value, String field) {
    Objects.requireNonNull(value, field);
    if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException(field + " must be between 0 and 1");
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
