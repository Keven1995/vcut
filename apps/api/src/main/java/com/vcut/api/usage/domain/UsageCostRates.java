package com.vcut.api.usage.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record UsageCostRates(
    BigDecimal transcriptionPerMinute,
    BigDecimal multimodalPerMinute,
    BigDecimal llmPerThousandTokens,
    BigDecimal cpuPerSecond,
    BigDecimal gpuPerSecond,
    BigDecimal storagePerGiBMonth,
    BigDecimal bandwidthPerGiB) {

  public UsageCostRates {
    for (BigDecimal rate :
        new BigDecimal[] {
          transcriptionPerMinute,
          multimodalPerMinute,
          llmPerThousandTokens,
          cpuPerSecond,
          gpuPerSecond,
          storagePerGiBMonth,
          bandwidthPerGiB
        }) {
      Objects.requireNonNull(rate, "cost rate");
      if (rate.signum() < 0) {
        throw new IllegalArgumentException("cost rates must not be negative");
      }
    }
  }
}
