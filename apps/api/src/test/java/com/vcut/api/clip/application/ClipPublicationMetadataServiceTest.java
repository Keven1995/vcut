package com.vcut.api.clip.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vcut.api.clip.domain.AspectRatio;
import com.vcut.api.clip.domain.CaptionCue;
import com.vcut.api.clip.domain.CaptionPreset;
import com.vcut.api.clip.domain.Clip;
import com.vcut.api.clip.domain.ClipScore;
import com.vcut.api.clip.domain.ClipVersion;
import com.vcut.api.clip.domain.CropSettings;
import com.vcut.api.clip.domain.PublicationMetadata;
import com.vcut.api.clip.domain.PublicationMetadataStatus;
import com.vcut.api.clip.domain.PublicationPlatform;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClipPublicationMetadataServiceTest {

  private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID CLIP_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

  private final ClipRepository clips = mock(ClipRepository.class);
  private final PublicationMetadataRepository metadata = mock(PublicationMetadataRepository.class);
  private final ClipPublicationMetadataService service =
      new ClipPublicationMetadataService(clips, metadata, Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeEach
  void setUp() {
    Clip clip =
        Clip.created(
            CLIP_ID,
            USER_ID,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            new ClipScore(
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE),
            NOW);
    ClipVersion version =
        ClipVersion.initial(
            UUID.randomUUID(),
            clip,
            BigDecimal.ZERO,
            BigDecimal.TEN,
            AspectRatio.PORTRAIT,
            CropSettings.centered(),
            CaptionPreset.MINIMAL,
            CaptionPreset.MINIMAL.defaultStyle(),
            List.of(
                new CaptionCue(
                    UUID.randomUUID(),
                    "Boss fight moment! Wow, that was incredible.",
                    BigDecimal.ZERO,
                    BigDecimal.ONE)),
            NOW);
    when(clips.findByIdForUser(CLIP_ID, USER_ID))
        .thenReturn(Optional.of(new ClipAggregate(clip, version)));
  }

  @Test
  void createsReviewablePlatformSpecificDraftsWithoutPublishing() {
    when(metadata.find(CLIP_ID, 1, PublicationPlatform.SHORTS)).thenReturn(Optional.empty());
    when(metadata.save(org.mockito.ArgumentMatchers.any(PublicationMetadata.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    PublicationMetadata draft = service.generate(USER_ID, CLIP_ID, PublicationPlatform.SHORTS);

    assertThat(draft.status()).isEqualTo(PublicationMetadataStatus.DRAFT);
    assertThat(draft.title()).isEqualTo("Boss fight moment");
    assertThat(draft.hashtags()).contains("#boss", "#fight", "#shorts");
    assertThat(draft.reviewedAt()).isNull();
    verify(metadata).save(draft);
  }

  @Test
  void generationIsIdempotentAndDoesNotReplaceTheUsersEdits() {
    PublicationMetadata reviewed = draft().review(NOW.plusSeconds(10));
    when(metadata.find(CLIP_ID, 1, PublicationPlatform.REELS)).thenReturn(Optional.of(reviewed));

    PublicationMetadata result = service.generate(USER_ID, CLIP_ID, PublicationPlatform.REELS);

    assertThat(result).isEqualTo(reviewed);
    verify(metadata, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void explicitReviewChangesStateButDoesNotPublish() {
    when(metadata.find(CLIP_ID, 1, PublicationPlatform.TIKTOK))
        .thenReturn(Optional.of(draft(PublicationPlatform.TIKTOK)));
    when(metadata.update(org.mockito.ArgumentMatchers.any(PublicationMetadata.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    PublicationMetadata reviewed = service.review(USER_ID, CLIP_ID, PublicationPlatform.TIKTOK);

    assertThat(reviewed.status()).isEqualTo(PublicationMetadataStatus.REVIEWED);
    assertThat(reviewed.reviewedAt()).isEqualTo(NOW);
  }

  private static PublicationMetadata draft() {
    return draft(PublicationPlatform.REELS);
  }

  private static PublicationMetadata draft(PublicationPlatform platform) {
    return new PublicationMetadata(
        UUID.randomUUID(),
        CLIP_ID,
        USER_ID,
        1,
        platform,
        "Generated title",
        "Generated description",
        List.of("#" + platform.name().toLowerCase()),
        PublicationMetadataStatus.DRAFT,
        NOW,
        NOW,
        null);
  }
}
