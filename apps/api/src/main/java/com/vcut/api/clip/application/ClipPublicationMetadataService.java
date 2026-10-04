package com.vcut.api.clip.application;

import com.vcut.api.clip.domain.CaptionCue;
import com.vcut.api.clip.domain.ClipVersion;
import com.vcut.api.clip.domain.PublicationMetadata;
import com.vcut.api.clip.domain.PublicationMetadataStatus;
import com.vcut.api.clip.domain.PublicationPlatform;
import com.vcut.api.shared.errors.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClipPublicationMetadataService {

  private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{3,}");

  private final ClipRepository clips;
  private final PublicationMetadataRepository metadata;
  private final Clock clock;

  @Autowired
  public ClipPublicationMetadataService(
      ClipRepository clips, PublicationMetadataRepository metadata) {
    this(clips, metadata, Clock.systemUTC());
  }

  ClipPublicationMetadataService(
      ClipRepository clips, PublicationMetadataRepository metadata, Clock clock) {
    this.clips = clips;
    this.metadata = metadata;
    this.clock = clock;
  }

  @Transactional
  public PublicationMetadata generate(UUID userId, UUID clipId, PublicationPlatform platform) {
    ClipVersion version = currentVersion(userId, clipId);
    return metadata
        .find(clipId, version.editVersion(), platform)
        .orElseGet(() -> metadata.save(draft(version, platform, clock.instant())));
  }

  @Transactional(readOnly = true)
  public List<PublicationMetadata> list(UUID userId, UUID clipId) {
    ClipVersion version = currentVersion(userId, clipId);
    return metadata.findAll(clipId, version.editVersion());
  }

  @Transactional
  public PublicationMetadata edit(
      UUID userId,
      UUID clipId,
      PublicationPlatform platform,
      String title,
      String description,
      List<String> hashtags) {
    ClipVersion version = currentVersion(userId, clipId);
    PublicationMetadata current =
        metadata
            .find(clipId, version.editVersion(), platform)
            .orElseThrow(() -> new ResourceNotFoundException("Publication metadata not found."));
    return metadata.update(current.edit(title, description, hashtags, clock.instant()));
  }

  @Transactional
  public PublicationMetadata review(UUID userId, UUID clipId, PublicationPlatform platform) {
    ClipVersion version = currentVersion(userId, clipId);
    PublicationMetadata current =
        metadata
            .find(clipId, version.editVersion(), platform)
            .orElseThrow(() -> new ResourceNotFoundException("Publication metadata not found."));
    return current.status() == PublicationMetadataStatus.REVIEWED
        ? current
        : metadata.update(current.review(clock.instant()));
  }

  private ClipVersion currentVersion(UUID userId, UUID clipId) {
    return clips
        .findByIdForUser(clipId, userId)
        .map(ClipAggregate::version)
        .orElseThrow(() -> new ResourceNotFoundException("Clip not found."));
  }

  private static PublicationMetadata draft(
      ClipVersion version, PublicationPlatform platform, Instant now) {
    String text =
        version.captionCues().stream()
            .map(CaptionCue::text)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .reduce((left, right) -> left + " " + right)
            .orElse("A moment worth sharing.");
    String title = firstSentence(text, 100);
    String description = text.length() <= 2_200 ? text : text.substring(0, 2_200);
    return new PublicationMetadata(
        UUID.randomUUID(),
        version.clipId(),
        version.userId(),
        version.editVersion(),
        platform,
        title,
        description,
        hashtags(text, platform),
        PublicationMetadataStatus.DRAFT,
        now,
        now,
        null);
  }

  private static String firstSentence(String text, int maxLength) {
    int sentenceEnd = -1;
    for (char delimiter : new char[] {'.', '!', '?'}) {
      int found = text.indexOf(delimiter);
      if (found > 0 && (sentenceEnd < 0 || found < sentenceEnd)) {
        sentenceEnd = found;
      }
    }
    String title = sentenceEnd > 0 ? text.substring(0, sentenceEnd).trim() : text.trim();
    return title.length() <= maxLength ? title : title.substring(0, maxLength).trim();
  }

  private static List<String> hashtags(String text, PublicationPlatform platform) {
    LinkedHashSet<String> tags = new LinkedHashSet<>();
    Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
    while (matcher.find() && tags.size() < 7) {
      tags.add("#" + matcher.group());
    }
    String platformTag =
        switch (platform) {
          case SHORTS -> "#shorts";
          case REELS -> "#reels";
          case TIKTOK -> "#tiktok";
        };
    tags.add(platformTag);
    return new ArrayList<>(tags);
  }
}
