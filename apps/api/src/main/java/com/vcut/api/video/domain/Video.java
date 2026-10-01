package com.vcut.api.video.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Video(
    UUID id,
    UUID userId,
    UUID projectId,
    String objectKey,
    String originalFilename,
    String declaredContentType,
    long declaredSizeBytes,
    Long actualSizeBytes,
    String checksumSha256,
    BigDecimal durationSeconds,
    Integer width,
    Integer height,
    BigDecimal frameRate,
    Boolean hasAudio,
    VideoUploadStatus status,
    String failureCode,
    Instant createdAt,
    Instant updatedAt) {

  public Video {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(projectId, "projectId");
    requireText(objectKey, "objectKey");
    requireText(originalFilename, "originalFilename");
    requireText(declaredContentType, "declaredContentType");
    if (declaredSizeBytes <= 0) {
      throw new IllegalArgumentException("declaredSizeBytes must be positive");
    }
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (actualSizeBytes != null && actualSizeBytes <= 0) {
      throw new IllegalArgumentException("actualSizeBytes must be positive");
    }
    if (durationSeconds != null && durationSeconds.signum() < 0) {
      throw new IllegalArgumentException("durationSeconds must not be negative");
    }
    if (width != null && width <= 0 || height != null && height <= 0) {
      throw new IllegalArgumentException("video dimensions must be positive");
    }
    if (frameRate != null && frameRate.signum() < 0) {
      throw new IllegalArgumentException("frameRate must not be negative");
    }
  }

  public static Video uploading(
      UUID id,
      UUID userId,
      UUID projectId,
      String objectKey,
      String originalFilename,
      String declaredContentType,
      long declaredSizeBytes,
      Instant now) {
    return new Video(
        id,
        userId,
        projectId,
        objectKey,
        originalFilename,
        declaredContentType,
        declaredSizeBytes,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        VideoUploadStatus.UPLOADING,
        null,
        now,
        now);
  }

  public Video uploaded(long actualSizeBytes, String checksumSha256, Instant now) {
    if (status != VideoUploadStatus.UPLOADING) {
      throw new IllegalStateException("Only an uploading video can be confirmed");
    }
    return new Video(
        id,
        userId,
        projectId,
        objectKey,
        originalFilename,
        declaredContentType,
        declaredSizeBytes,
        actualSizeBytes,
        checksumSha256,
        durationSeconds,
        width,
        height,
        frameRate,
        hasAudio,
        VideoUploadStatus.UPLOADED,
        null,
        createdAt,
        now);
  }

  public Video validating(Instant now) {
    if (status != VideoUploadStatus.UPLOADED) {
      throw new IllegalStateException("Only an uploaded video can be validated");
    }
    return new Video(
        id,
        userId,
        projectId,
        objectKey,
        originalFilename,
        declaredContentType,
        declaredSizeBytes,
        actualSizeBytes,
        checksumSha256,
        durationSeconds,
        width,
        height,
        frameRate,
        hasAudio,
        VideoUploadStatus.VALIDATING,
        null,
        createdAt,
        now);
  }

  public Video validated(
      VideoUploadStatus validationStatus,
      Long actualSize,
      java.math.BigDecimal duration,
      Integer videoWidth,
      Integer videoHeight,
      java.math.BigDecimal fps,
      Boolean audio,
      String validationFailureCode,
      Instant now) {
    if (status != VideoUploadStatus.VALIDATING) {
      throw new IllegalStateException("Only a validating video can receive validation results");
    }
    if (validationStatus != VideoUploadStatus.READY
        && validationStatus != VideoUploadStatus.REJECTED) {
      throw new IllegalArgumentException("validationStatus must be READY or REJECTED");
    }
    return new Video(
        id,
        userId,
        projectId,
        objectKey,
        originalFilename,
        declaredContentType,
        declaredSizeBytes,
        actualSize,
        checksumSha256,
        duration,
        videoWidth,
        videoHeight,
        fps,
        audio,
        validationStatus,
        validationFailureCode,
        createdAt,
        now);
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
