package com.vcut.api.clip.presentation;

import com.vcut.api.clip.domain.PublicationMetadata;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PublicationMetadataResponse(
    UUID id,
    UUID clipId,
    int editVersion,
    String platform,
    String title,
    String description,
    List<String> hashtags,
    String status,
    Instant updatedAt,
    Instant reviewedAt) {

  public static PublicationMetadataResponse from(PublicationMetadata metadata) {
    return new PublicationMetadataResponse(
        metadata.id(),
        metadata.clipId(),
        metadata.editVersion(),
        metadata.platform().name(),
        metadata.title(),
        metadata.description(),
        metadata.hashtags(),
        metadata.status().name(),
        metadata.updatedAt(),
        metadata.reviewedAt());
  }
}
