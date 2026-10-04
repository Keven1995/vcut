package com.vcut.api.usage.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.vcut.api.usage.domain.UsageCostRates;
import com.vcut.api.usage.domain.UsageMetrics;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class UsageCostCalculatorTest {

  @Test
  void estimatesTranscriptionMultimodalLlmComputeStorageAndBandwidthCosts() {
    var rates =
        new UsageCostRates(
            BigDecimal.valueOf(0.20),
            BigDecimal.valueOf(0.10),
            BigDecimal.valueOf(0.01),
            BigDecimal.valueOf(0.001),
            BigDecimal.valueOf(0.01),
            BigDecimal.valueOf(0.02),
            BigDecimal.valueOf(0.05));
    var metrics =
        new UsageMetrics(
            BigDecimal.TEN,
            BigDecimal.valueOf(5),
            5_000,
            BigDecimal.valueOf(100),
            BigDecimal.valueOf(20),
            1_073_741_824L,
            2_147_483_648L,
            2);

    BigDecimal cost = new UsageCostCalculator(rates).calculate(metrics);

    assertThat(cost).isEqualByComparingTo("2.970000");
  }
}
