package com.vcut.api.usage.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record UsageMetrics(
    BigDecimal transcriptionMinutes,
    BigDecimal multimodalMinutes,
    long llmTokens,
    BigDecimal cpuSeconds,
    BigDecimal gpuSeconds,
    long storageBytes,
    long bandwidthBytes,
    long renders) {

  public UsageMetrics {
    Objects.requireNonNull(transcriptionMinutes, "transcriptionMinutes");
    Objects.requireNonNull(multimodalMinutes, "multimodalMinutes");
    Objects.requireNonNull(cpuSeconds, "cpuSeconds");
    Objects.requireNonNull(gpuSeconds, "gpuSeconds");
    if (transcriptionMinutes.signum() < 0
        || multimodalMinutes.signum() < 0
        || llmTokens < 0
        || cpuSeconds.signum() < 0
        || gpuSeconds.signum() < 0
        || storageBytes < 0
        || bandwidthBytes < 0
        || renders < 0) {
      throw new IllegalArgumentException("usage metrics must not be negative");
    }
  }

  public static UsageMetrics empty() {
    return new UsageMetrics(
        BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0);
  }
}
