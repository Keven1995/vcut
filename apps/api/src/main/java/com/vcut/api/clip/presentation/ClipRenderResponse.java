package com.vcut.api.clip.presentation;

import com.vcut.api.clip.domain.ClipRender;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ClipRenderResponse(
    UUID id,
    UUID clipId,
    int editVersion,
    String status,
    int progress,
    BigDecimal durationSeconds,
    Integer width,
    Integer height,
    String aspectRatio,
    String errorCode,
    String errorMessage,
    Instant createdAt,
    Instant updatedAt) {

  public static ClipRenderResponse from(ClipRender render) {
    return new ClipRenderResponse(
        render.id(),
        render.clipId(),
        render.editVersion(),
        render.status().name(),
        render.progress(),
        render.outputDurationSeconds(),
        render.outputWidth(),
        render.outputHeight(),
        render.outputAspectRatio() == null ? null : render.outputAspectRatio().value(),
        render.errorCode(),
        render.errorMessage(),
        render.createdAt(),
        render.updatedAt());
  }
}
