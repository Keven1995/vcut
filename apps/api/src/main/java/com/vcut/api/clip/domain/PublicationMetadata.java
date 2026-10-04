package com.vcut.api.clip.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record PublicationMetadata(
    UUID id,
    UUID clipId,
    UUID userId,
    int editVersion,
    PublicationPlatform platform,
    String title,
    String description,
    List<String> hashtags,
    PublicationMetadataStatus status,
    Instant createdAt,
    Instant updatedAt,
    Instant reviewedAt) {

  private static final Pattern HASHTAG = Pattern.compile("^#[\\p{L}\\p{N}_]{1,63}$");

  public PublicationMetadata {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(clipId, "clipId");
    Objects.requireNonNull(userId, "userId");
    if (editVersion < 1) {
      throw new IllegalArgumentException("editVersion must be positive");
    }
    Objects.requireNonNull(platform, "platform");
    if (title == null || title.isBlank() || title.length() > 100) {
      throw new IllegalArgumentException("title must contain 1-100 characters");
    }
    if (description == null || description.length() > 2_200) {
      throw new IllegalArgumentException("description must contain at most 2200 characters");
    }
    hashtags = List.copyOf(Objects.requireNonNull(hashtags, "hashtags"));
    if (hashtags.size() > 20
        || hashtags.stream().anyMatch(tag -> tag == null || !HASHTAG.matcher(tag).matches())
        || hashtags.stream().distinct().count() != hashtags.size()) {
      throw new IllegalArgumentException("hashtags must be unique and valid");
    }
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (status == PublicationMetadataStatus.REVIEWED && reviewedAt == null) {
      throw new IllegalArgumentException("reviewed metadata requires reviewedAt");
    }
    if (status == PublicationMetadataStatus.DRAFT && reviewedAt != null) {
      throw new IllegalArgumentException("draft metadata must not have reviewedAt");
    }
  }

  public PublicationMetadata edit(
      String newTitle, String newDescription, List<String> newHashtags, Instant now) {
    return new PublicationMetadata(
        id,
        clipId,
        userId,
        editVersion,
        platform,
        newTitle,
        newDescription,
        newHashtags,
        PublicationMetadataStatus.DRAFT,
        createdAt,
        now,
        null);
  }

  public PublicationMetadata review(Instant now) {
    return new PublicationMetadata(
        id,
        clipId,
        userId,
        editVersion,
        platform,
        title,
        description,
        hashtags,
        PublicationMetadataStatus.REVIEWED,
        createdAt,
        now,
        now);
  }
}
