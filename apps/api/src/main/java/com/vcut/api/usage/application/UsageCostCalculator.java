package com.vcut.api.usage.application;

import com.vcut.api.usage.domain.UsageCostRates;
import com.vcut.api.usage.domain.UsageMetrics;
import java.math.BigDecimal;
import java.math.RoundingMode;

public class UsageCostCalculator {

  private static final BigDecimal BYTES_PER_GIB = BigDecimal.valueOf(1_073_741_824L);
  private final UsageCostRates rates;

  public UsageCostCalculator(UsageCostRates rates) {
    this.rates = rates;
  }

  public BigDecimal calculate(UsageMetrics metrics) {
    BigDecimal transcription =
        metrics.transcriptionMinutes().multiply(rates.transcriptionPerMinute());
    BigDecimal multimodal = metrics.multimodalMinutes().multiply(rates.multimodalPerMinute());
    BigDecimal llm =
        BigDecimal.valueOf(metrics.llmTokens())
            .divide(BigDecimal.valueOf(1_000), 12, RoundingMode.HALF_UP)
            .multiply(rates.llmPerThousandTokens());
    BigDecimal cpu = metrics.cpuSeconds().multiply(rates.cpuPerSecond());
    BigDecimal gpu = metrics.gpuSeconds().multiply(rates.gpuPerSecond());
    BigDecimal storage =
        BigDecimal.valueOf(metrics.storageBytes())
            .divide(BYTES_PER_GIB, 12, RoundingMode.HALF_UP)
            .multiply(rates.storagePerGiBMonth());
    BigDecimal bandwidth =
        BigDecimal.valueOf(metrics.bandwidthBytes())
            .divide(BYTES_PER_GIB, 12, RoundingMode.HALF_UP)
            .multiply(rates.bandwidthPerGiB());
    return transcription
        .add(multimodal)
        .add(llm)
        .add(cpu)
        .add(gpu)
        .add(storage)
        .add(bandwidth)
        .setScale(6, RoundingMode.HALF_UP);
  }
}
