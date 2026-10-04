package com.vcut.api.usage.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.vcut.api.usage.domain.PlanCode;
import com.vcut.api.usage.domain.RetentionAssetType;
import com.vcut.api.usage.infrastructure.UsageProperties;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class UsagePropertiesTest {

  @Test
  void resolvesPlanSpecificLimitsAndRetentionWithoutEmbeddingPlanRulesInDomain() {
    UsageProperties properties =
        new UsageProperties(
            new UsageProperties.PlanSettings(
                60, 500, 3_600, 5_000, 1, 1_920, 1_920, 0, newRetention(30, 7, 3, 7, 30, 30, 7)),
            new UsageProperties.PlanSettings(
                600,
                2_000,
                7_200,
                50_000,
                3,
                3_840,
                3_840,
                5,
                newRetention(90, 30, 14, 30, 90, 90, 14)),
            new UsageProperties.CostSettings(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO));

    PlanLimits free = properties.limitsFor(PlanCode.FREE);
    PlanLimits pro = properties.limitsFor(PlanCode.PRO);

    assertThat(free.monthlyProcessingMinutes()).isEqualByComparingTo("60");
    assertThat(free.maxFileSizeBytes()).isEqualTo(500);
    assertThat(free.retentionPolicy().retentionFor(RetentionAssetType.AUDIO))
        .isEqualTo(Duration.ofDays(7));
    assertThat(free.retentionPolicy().retentionFor(RetentionAssetType.NORMALIZED))
        .isEqualTo(Duration.ofDays(30));
    assertThat(pro.monthlyProcessingMinutes()).isEqualByComparingTo("600");
    assertThat(pro.workerPriority()).isEqualTo(5);
    assertThat(pro.maxStorageBytes()).isEqualTo(50_000);
    assertThat(pro.retentionPolicy().retentionFor(RetentionAssetType.FINAL))
        .isEqualTo(Duration.ofDays(90));
  }

  private static UsageProperties.RetentionSettings newRetention(
      long original,
      long audio,
      long frames,
      long preview,
      long finalMedia,
      long thumbnail,
      long failedArtifacts) {
    return new UsageProperties.RetentionSettings(
        original, audio, frames, preview, finalMedia, thumbnail, failedArtifacts);
  }
}
