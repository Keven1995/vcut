package com.vcut.api.clip.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ClipVersion(
    UUID id,
    UUID clipId,
    UUID userId,
    UUID projectId,
    UUID videoId,
    UUID candidateId,
    int editVersion,
    BigDecimal startSeconds,
    BigDecimal endSeconds,
    AspectRatio aspectRatio,
    CropSettings crop,
    CaptionPreset captionPreset,
    CaptionStyle captionStyle,
    List<CaptionCue> captionCues,
    Instant createdAt) {

  public ClipVersion {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(clipId, "clipId");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(projectId, "projectId");
    Objects.requireNonNull(videoId, "videoId");
    Objects.requireNonNull(candidateId, "candidateId");
    if (editVersion < 1) {
      throw new IllegalArgumentException("editVersion must be positive");
    }
    Objects.requireNonNull(startSeconds, "startSeconds");
    Objects.requireNonNull(endSeconds, "endSeconds");
    BigDecimal duration = endSeconds.subtract(startSeconds);
    if (startSeconds.signum() < 0
        || endSeconds.compareTo(startSeconds) <= 0
        || duration.compareTo(BigDecimal.valueOf(90)) > 0) {
      throw new IllegalArgumentException("clip interval must be positive and at most 90 seconds");
    }
    Objects.requireNonNull(aspectRatio, "aspectRatio");
    Objects.requireNonNull(crop, "crop");
    Objects.requireNonNull(captionPreset, "captionPreset");
    Objects.requireNonNull(captionStyle, "captionStyle");
    captionCues = List.copyOf(Objects.requireNonNull(captionCues, "captionCues"));
    BigDecimal previousEnd = BigDecimal.ZERO;
    for (CaptionCue cue : captionCues) {
      if (cue.endSeconds().compareTo(duration) > 0
          || cue.startSeconds().compareTo(previousEnd) < 0) {
        throw new IllegalArgumentException("caption cues must be relative and monotonic");
      }
      previousEnd = cue.endSeconds();
    }
    Objects.requireNonNull(createdAt, "createdAt");
  }

  public static ClipVersion initial(
      UUID id,
      Clip clip,
      BigDecimal startSeconds,
      BigDecimal endSeconds,
      AspectRatio aspectRatio,
      CropSettings crop,
      CaptionPreset captionPreset,
      CaptionStyle captionStyle,
      List<CaptionCue> captionCues,
      Instant now) {
    return new ClipVersion(
        id,
        clip.id(),
        clip.userId(),
        clip.projectId(),
        clip.videoId(),
        clip.candidateId(),
        1,
        startSeconds,
        endSeconds,
        aspectRatio,
        crop,
        captionPreset,
        captionStyle,
        captionCues,
        now);
  }

  public ClipVersion next(
      UUID id,
      BigDecimal newStart,
      BigDecimal newEnd,
      AspectRatio newAspectRatio,
      CropSettings newCrop,
      CaptionPreset newCaptionPreset,
      CaptionStyle newCaptionStyle,
      List<CaptionCue> newCues,
      Instant now) {
    return new ClipVersion(
        id,
        clipId,
        userId,
        projectId,
        videoId,
        candidateId,
        editVersion + 1,
        newStart,
        newEnd,
        newAspectRatio,
        newCrop,
        newCaptionPreset,
        newCaptionStyle,
        newCues,
        now);
  }
}
