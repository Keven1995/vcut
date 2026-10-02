package com.vcut.api.clip.presentation;

import com.vcut.api.clip.domain.ClipCandidate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ClipCandidateResponse(
    UUID id,
    UUID analysisRunId,
    UUID videoId,
    String variant,
    String status,
    BigDecimal startSeconds,
    BigDecimal endSeconds,
    String group,
    String title,
    String description,
    String justification,
    Instant createdAt,
    Instant updatedAt) {

  public static ClipCandidateResponse from(ClipCandidate candidate) {
    return new ClipCandidateResponse(
        candidate.id(),
        candidate.analysisRunId(),
        candidate.videoId(),
        candidate.variant().name(),
        candidate.status().name(),
        candidate.startSeconds(),
        candidate.endSeconds(),
        candidate.group(),
        candidate.title(),
        candidate.description(),
        candidate.justification(),
        candidate.createdAt(),
        candidate.updatedAt());
  }
}
