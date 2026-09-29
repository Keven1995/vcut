package com.vcut.api.video.presentation;

import com.vcut.api.video.application.ObjectStorage;
import com.vcut.api.video.domain.Video;
import com.vcut.api.video.domain.VideoUploadStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record VideoResponse(
    UUID id,
    UUID projectId,
    String objectKey,
    String originalFilename,
    String declaredContentType,
    long declaredSizeBytes,
    Long actualSizeBytes,
    BigDecimal durationSeconds,
    Integer width,
    Integer height,
    BigDecimal frameRate,
    Boolean hasAudio,
    VideoUploadStatus status,
    String failureCode,
    Instant createdAt,
    Instant updatedAt,
    String uploadUrl,
    Instant uploadUrlExpiresAt) {

  public static VideoResponse from(Video video) {
    return from(video, null);
  }

  public static VideoResponse from(Video video, ObjectStorage.PresignedUpload upload) {
    return new VideoResponse(
        video.id(),
        video.projectId(),
        video.objectKey(),
        video.originalFilename(),
        video.declaredContentType(),
        video.declaredSizeBytes(),
        video.actualSizeBytes(),
        video.durationSeconds(),
        video.width(),
        video.height(),
        video.frameRate(),
        video.hasAudio(),
        video.status(),
        video.failureCode(),
        video.createdAt(),
        video.updatedAt(),
        upload == null ? null : upload.url(),
        upload == null ? null : upload.expiresAt());
  }
}
