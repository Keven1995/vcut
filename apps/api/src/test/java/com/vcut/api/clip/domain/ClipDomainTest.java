package com.vcut.api.clip.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
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
}
