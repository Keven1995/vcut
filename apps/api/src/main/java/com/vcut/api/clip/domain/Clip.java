package com.vcut.api.clip.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Clip(
    UUID id,
    UUID userId,
    UUID projectId,
    UUID videoId,
    UUID candidateId,
    ClipScore score,
    int currentEditVersion,
    ClipStatus status,
    int generationProgress,
    String outputObjectKey,
    Integer outputWidth,
    Integer outputHeight,
    BigDecimal outputDurationSeconds,
    AspectRatio outputAspectRatio,
    Integer outputEditVersion,
    String errorCode,
    String errorMessage,
    Instant createdAt,
    Instant updatedAt) {

  public Clip {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(projectId, "projectId");
    Objects.requireNonNull(videoId, "videoId");
    Objects.requireNonNull(candidateId, "candidateId");
    Objects.requireNonNull(score, "score");
    if (currentEditVersion < 1) {
      throw new IllegalArgumentException("currentEditVersion must be positive");
    }
    Objects.requireNonNull(status, "status");
    if (generationProgress < 0 || generationProgress > 100) {
      throw new IllegalArgumentException("generationProgress must be between 0 and 100");
    }
    if (outputObjectKey != null && outputObjectKey.isBlank()) {
      throw new IllegalArgumentException("outputObjectKey must not be blank");
    }
    if (outputWidth != null && outputWidth <= 0 || outputHeight != null && outputHeight <= 0) {
      throw new IllegalArgumentException("output dimensions must be positive");
    }
    if (outputDurationSeconds != null && outputDurationSeconds.signum() <= 0) {
      throw new IllegalArgumentException("outputDurationSeconds must be positive");
    }
    if (outputEditVersion != null && outputEditVersion < 1) {
      throw new IllegalArgumentException("outputEditVersion must be positive");
    }
    if (status == ClipStatus.READY
        && (outputObjectKey == null
            || outputWidth == null
            || outputHeight == null
            || outputDurationSeconds == null
            || outputAspectRatio == null
            || outputEditVersion == null)) {
      throw new IllegalArgumentException("ready clips must have an output");
    }
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  public static Clip created(
      UUID id,
      UUID userId,
      UUID projectId,
      UUID videoId,
      UUID candidateId,
      ClipScore score,
      Instant now) {
    return new Clip(
        id,
        userId,
        projectId,
        videoId,
        candidateId,
        score,
        1,
        ClipStatus.QUEUED,
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

  public Clip edited(int editVersion, Instant now) {
    if (editVersion != currentEditVersion + 1) {
      throw new IllegalArgumentException("edit versions must increase by one");
    }
    return new Clip(
        id,
        userId,
        projectId,
        videoId,
        candidateId,
        score,
        editVersion,
        ClipStatus.QUEUED,
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

  public Clip processing(Instant now) {
    return processing(0, now);
  }

  public Clip processing(int progress, Instant now) {
    if (status == ClipStatus.READY
        && outputEditVersion != null
        && outputEditVersion == currentEditVersion) {
      return this;
    }
    return withState(
        ClipStatus.PROCESSING, progress, null, null, null, null, null, null, null, now);
  }

  public Clip ready(
      int editVersion,
      String outputKey,
      BigDecimal durationSeconds,
      int width,
      int height,
      AspectRatio aspectRatio,
      Instant now) {
    if (editVersion != currentEditVersion) {
      throw new IllegalArgumentException(
          "generation result does not match the current edit version");
    }
    Objects.requireNonNull(aspectRatio, "aspectRatio");
    if (width <= 0
        || height <= 0
        || durationSeconds == null
        || durationSeconds.signum() <= 0
        || durationSeconds.compareTo(BigDecimal.valueOf(90)) > 0) {
      throw new IllegalArgumentException("generation result metadata is invalid");
    }
    if ((aspectRatio == AspectRatio.PORTRAIT && width * 16L != height * 9L)
        || (aspectRatio == AspectRatio.LANDSCAPE && width * 9L != height * 16L)) {
      throw new IllegalArgumentException("generation dimensions do not match the aspect ratio");
    }
    return withState(
        ClipStatus.READY,
        100,
        outputKey,
        width,
        height,
        durationSeconds,
        aspectRatio,
        editVersion,
        null,
        now);
  }

  public Clip failed(int editVersion, String code, String message, Instant now) {
    if (editVersion != currentEditVersion) {
      throw new IllegalArgumentException(
          "generation failure does not match the current edit version");
    }
    if (code == null || code.isBlank() || message == null || message.isBlank()) {
      throw new IllegalArgumentException("generation failure must contain code and message");
    }
    return withState(
            ClipStatus.FAILED,
            generationProgress,
            null,
            null,
            null,
            null,
            null,
            editVersion,
            code,
            now)
        .withErrorMessage(message);
  }

  public Clip queued(Instant now) {
    if (status == ClipStatus.READY
        && outputEditVersion != null
        && outputEditVersion == currentEditVersion) {
      return this;
    }
    return withState(ClipStatus.QUEUED, 0, null, null, null, null, null, null, null, now);
  }

  private Clip withState(
      ClipStatus newStatus,
      int progress,
      String outputKey,
      Integer width,
      Integer height,
      BigDecimal duration,
      AspectRatio aspectRatio,
      Integer resultVersion,
      String error,
      Instant now) {
    return new Clip(
        id,
        userId,
        projectId,
        videoId,
        candidateId,
        score,
        currentEditVersion,
        newStatus,
        progress,
        outputKey,
        width,
        height,
        duration,
        aspectRatio,
        resultVersion,
        error,
        null,
        createdAt,
        now);
  }

  private Clip withErrorMessage(String message) {
    return new Clip(
        id,
        userId,
        projectId,
        videoId,
        candidateId,
        score,
        currentEditVersion,
        status,
        generationProgress,
        outputObjectKey,
        outputWidth,
        outputHeight,
        outputDurationSeconds,
        outputAspectRatio,
        outputEditVersion,
        errorCode,
        message,
        createdAt,
        updatedAt);
  }
}
