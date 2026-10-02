package com.vcut.api.clip.presentation;

import com.vcut.api.clip.domain.ClipAnalysisRun;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ClipAnalysisRunResponse(
    UUID id,
    UUID videoId,
    int pipelineVersion,
    String durationPreference,
    BigDecimal customDurationSeconds,
    BigDecimal durationSeconds,
    String language,
    String status,
    String errorCode,
    String errorMessage,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt) {

  public static ClipAnalysisRunResponse from(ClipAnalysisRun run) {
    return new ClipAnalysisRunResponse(
        run.id(),
        run.videoId(),
        run.pipelineVersion(),
        run.durationPreference().name(),
        run.customDurationSeconds(),
        run.durationSeconds(),
        run.language(),
        run.status().name(),
        run.errorCode(),
        run.errorMessage(),
        run.createdAt(),
        run.updatedAt(),
        run.completedAt());
  }
}
