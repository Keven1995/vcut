package com.vcut.api.clip.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClipDomainTest {

  @Test
  void rejectsCustomDurationAboveNinetySeconds() {
    assertThatThrownBy(
            () ->
                ClipAnalysisRun.queued(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    1,
                    DurationPreference.CUSTOM,
                    BigDecimal.valueOf(91),
                    BigDecimal.valueOf(91),
                    "en",
                    Instant.now()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsCandidateIntervalLongerThanNinetySeconds() {
    assertThatThrownBy(
            () ->
                new ClipCandidate(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    CandidateVariant.SHORT,
                    CandidateStatus.SUGGESTED,
                    BigDecimal.ZERO,
                    BigDecimal.valueOf(91),
                    "group",
                    "title",
                    "description",
                    "justification",
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    Instant.now(),
                    Instant.now()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsOnlySupportedAspectRatios() {
    assertThat(AspectRatio.fromValue("9:16")).isEqualTo(AspectRatio.PORTRAIT);
    assertThat(AspectRatio.fromValue("16:9")).isEqualTo(AspectRatio.LANDSCAPE);
    assertThatThrownBy(() -> AspectRatio.fromValue("1:1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsInvalidCaptionStyle() {
    assertThatThrownBy(
            () ->
                new CaptionStyle(
                    "Arial",
                    7,
                    400,
                    "white",
                    "#000000",
                    BigDecimal.ONE,
                    CaptionPosition.BOTTOM,
                    CaptionAnimation.NONE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void editCreatesAnImmutableNextVersion() {
    Instant now = Instant.now();
    Clip clip =
        Clip.created(
            UUID.randomUUID(),
            UUID.randomUUID(),
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
            now);
    ClipVersion first =
        ClipVersion.initial(
            UUID.randomUUID(),
            clip,
            BigDecimal.ZERO,
            BigDecimal.TEN,
            AspectRatio.PORTRAIT,
            CropSettings.centered(),
            CaptionPreset.MINIMAL,
            CaptionPreset.MINIMAL.defaultStyle(),
            List.of(new CaptionCue(UUID.randomUUID(), "Olá", BigDecimal.ZERO, BigDecimal.ONE)),
            now);
    ClipVersion second =
        first.next(
            UUID.randomUUID(),
            BigDecimal.ONE,
            BigDecimal.valueOf(11),
            AspectRatio.LANDSCAPE,
            CropSettings.centered(),
            CaptionPreset.BOLD,
            CaptionPreset.BOLD.defaultStyle(),
            first.captionCues(),
            now.plusSeconds(1));

    assertThat(first.editVersion()).isEqualTo(1);
    assertThat(second.editVersion()).isEqualTo(2);
    assertThat(second).isNotSameAs(first);
  }

  @Test
  void rejectsOutputDimensionsThatDoNotMatchRatio() {
    Clip clip =
        Clip.created(
            UUID.randomUUID(),
            UUID.randomUUID(),
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
            Instant.now());

    assertThatThrownBy(
            () ->
                clip.ready(
                    1,
                    "users/u/clips/c/v1/preview.mp4",
                    BigDecimal.TEN,
                    1920,
                    1080,
                    AspectRatio.PORTRAIT,
                    Instant.now()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
