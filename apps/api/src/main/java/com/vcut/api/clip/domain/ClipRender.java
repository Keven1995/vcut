package com.vcut.api.clip.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ClipRender(
    UUID id,
    UUID clipId,
    UUID userId,
    UUID projectId,
    int editVersion,
    RenderStatus status,
    int progress,
    String outputObjectKey,
    String thumbnailObjectKey,
    Integer outputWidth,
    Integer outputHeight,
    BigDecimal outputDurationSeconds,
    AspectRatio outputAspectRatio,
    String errorCode,
    String errorMessage,
    Instant createdAt,
    Instant updatedAt) {

  public ClipRender {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(clipId, "clipId");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(projectId, "projectId");
    if (editVersion < 1) {
      throw new IllegalArgumentException("editVersion must be positive");
    }
    Objects.requireNonNull(status, "status");
    if (progress < 0 || progress > 100) {
      throw new IllegalArgumentException("progress must be between 0 and 100");
    }
    if (outputObjectKey != null && outputObjectKey.isBlank()) {
      throw new IllegalArgumentException("outputObjectKey must not be blank");
    }
    if (thumbnailObjectKey != null && thumbnailObjectKey.isBlank()) {
      throw new IllegalArgumentException("thumbnailObjectKey must not be blank");
    }
    if ((outputWidth == null) != (outputHeight == null)
        || outputWidth != null && outputWidth <= 0
        || outputHeight != null && outputHeight <= 0) {
      throw new IllegalArgumentException("output dimensions must be both absent or positive");
    }
    if (outputDurationSeconds != null && outputDurationSeconds.signum() <= 0) {
      throw new IllegalArgumentException("outputDurationSeconds must be positive");
    }
    if (status == RenderStatus.READY
        && (outputObjectKey == null
            || thumbnailObjectKey == null
            || outputWidth == null
            || outputHeight == null
            || outputDurationSeconds == null
            || outputAspectRatio == null)) {
      throw new IllegalArgumentException("ready renders must have complete output metadata");
    }
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  public static ClipRender queued(
      UUID id, UUID clipId, UUID userId, UUID projectId, int editVersion, Instant now) {
    return new ClipRender(
        id,
        clipId,
        userId,
        projectId,
        editVersion,
        RenderStatus.QUEUED,
        0,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        now,
        now);
  }

  public ClipRender processing(int nextProgress, Instant now) {
    if (status == RenderStatus.READY || status == RenderStatus.FAILED) {
      return this;
    }
    int monotonicProgress = Math.max(progress, Math.min(99, nextProgress));
    return withState(
        RenderStatus.PROCESSING, monotonicProgress, null, null, null, null, null, null, now);
  }

  public ClipRender ready(
      String outputKey,
      String thumbnailKey,
      BigDecimal duration,
      int width,
      int height,
      AspectRatio aspectRatio,
      Instant now) {
    if (duration == null || duration.signum() <= 0 || width <= 0 || height <= 0) {
      throw new IllegalArgumentException("render result metadata is invalid");
    }
    if ((aspectRatio == AspectRatio.PORTRAIT && width * 16L != height * 9L)
        || (aspectRatio == AspectRatio.LANDSCAPE && width * 9L != height * 16L)) {
      throw new IllegalArgumentException("render dimensions do not match the aspect ratio");
    }
    return new ClipRender(
        id,
        clipId,
        userId,
        projectId,
        editVersion,
        RenderStatus.READY,
        100,
        outputKey,
        thumbnailKey,
        width,
        height,
        duration,
        Objects.requireNonNull(aspectRatio, "aspectRatio"),
        null,
        null,
        createdAt,
        now);
  }

  public ClipRender failed(String code, String message, Instant now) {
    if (code == null || code.isBlank() || message == null || message.isBlank()) {
      throw new IllegalArgumentException("render failure must contain code and message");
    }
    return withState(RenderStatus.FAILED, progress, null, null, null, null, null, code, now)
        .withErrorMessage(message);
  }

  public ClipRender retry(Instant now) {
    if (status != RenderStatus.FAILED) {
      return this;
    }
    return new ClipRender(
        id,
        clipId,
        userId,
        projectId,
        editVersion,
        RenderStatus.QUEUED,
        0,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        createdAt,
        now);
  }

  private ClipRender withState(
      RenderStatus nextStatus,
      int nextProgress,
      String outputKey,
      String thumbnailKey,
      Integer width,
      Integer height,
      BigDecimal duration,
      String code,
      Instant now) {
    return new ClipRender(
        id,
        clipId,
        userId,
        projectId,
        editVersion,
        nextStatus,
        nextProgress,
        outputKey,
        thumbnailKey,
        width,
        height,
        duration,
        null,
        code,
        null,
        createdAt,
        now);
  }

  private ClipRender withErrorMessage(String message) {
    return new ClipRender(
        id,
        clipId,
        userId,
        projectId,
        editVersion,
        status,
        progress,
        outputObjectKey,
        thumbnailObjectKey,
        outputWidth,
        outputHeight,
        outputDurationSeconds,
        outputAspectRatio,
        errorCode,
        message,
        createdAt,
        updatedAt);
  }
}
