package com.vcut.api.clip.presentation;

import com.vcut.api.clip.application.ClipAggregate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ClipResponse(
    UUID id,
    UUID videoId,
    UUID candidateId,
    String status,
    int progress,
    int editVersion,
    BigDecimal startSeconds,
    BigDecimal endSeconds,
    String aspectRatio,
    CropSettingsResponse crop,
    String captionPreset,
    CaptionStyleResponse captionStyle,
    List<CaptionCueResponse> captionCues,
    Instant createdAt,
    Instant updatedAt) {

  public static ClipResponse from(ClipAggregate aggregate) {
    var clip = aggregate.clip();
    var version = aggregate.version();
    return new ClipResponse(
        clip.id(),
        clip.videoId(),
        clip.candidateId(),
        clip.status().name(),
        clip.generationProgress(),
        version.editVersion(),
        version.startSeconds(),
        version.endSeconds(),
        version.aspectRatio().value(),
        CropSettingsResponse.from(version.crop()),
        version.captionPreset().name(),
        CaptionStyleResponse.from(version.captionStyle()),
        version.captionCues().stream().map(CaptionCueResponse::from).toList(),
        clip.createdAt(),
        clip.updatedAt());
  }
}
