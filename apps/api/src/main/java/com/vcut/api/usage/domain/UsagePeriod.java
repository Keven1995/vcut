package com.vcut.api.usage.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record UsagePeriod(
    UUID id,
    UUID userId,
    PlanCode planCode,
    Instant periodStart,
    Instant periodEnd,
    BigDecimal reservedMinutes,
    BigDecimal processedMinutes,
    long storedBytes,
    long renders,
    BigDecimal transcriptionMinutes,
    BigDecimal multimodalMinutes,
    long llmTokens,
    BigDecimal cpuSeconds,
    BigDecimal gpuSeconds,
    long bandwidthBytes,
    BigDecimal estimatedCost,
    Instant createdAt,
    Instant updatedAt) {

  public static UsagePeriod empty(
      UUID id,
      UUID userId,
      PlanCode planCode,
      Instant periodStart,
      Instant periodEnd,
      Instant now) {
    return new UsagePeriod(
        id,
        userId,
        planCode,
        periodStart,
        periodEnd,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        0,
        0,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        0,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        0,
        BigDecimal.ZERO,
        now,
        now);
  }

  public UsagePeriod {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(planCode, "planCode");
    Objects.requireNonNull(periodStart, "periodStart");
    Objects.requireNonNull(periodEnd, "periodEnd");
    Objects.requireNonNull(reservedMinutes, "reservedMinutes");
    Objects.requireNonNull(processedMinutes, "processedMinutes");
    Objects.requireNonNull(transcriptionMinutes, "transcriptionMinutes");
    Objects.requireNonNull(multimodalMinutes, "multimodalMinutes");
    Objects.requireNonNull(cpuSeconds, "cpuSeconds");
    Objects.requireNonNull(gpuSeconds, "gpuSeconds");
    Objects.requireNonNull(estimatedCost, "estimatedCost");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (!periodEnd.isAfter(periodStart)
        || reservedMinutes.signum() < 0
        || processedMinutes.signum() < 0
        || storedBytes < 0
        || renders < 0
        || transcriptionMinutes.signum() < 0
        || multimodalMinutes.signum() < 0
        || llmTokens < 0
        || cpuSeconds.signum() < 0
        || gpuSeconds.signum() < 0
        || bandwidthBytes < 0
        || estimatedCost.signum() < 0) {
      throw new IllegalArgumentException("usage period contains invalid totals");
    }
  }

  public UsagePeriod withProcessingTotals(
      BigDecimal newReservedMinutes, BigDecimal newProcessedMinutes, Instant now) {
    return new UsagePeriod(
        id,
        userId,
        planCode,
        periodStart,
        periodEnd,
        newReservedMinutes,
        newProcessedMinutes,
        storedBytes,
        renders,
        transcriptionMinutes,
        multimodalMinutes,
        llmTokens,
        cpuSeconds,
        gpuSeconds,
        bandwidthBytes,
        estimatedCost,
        createdAt,
        now);
  }

  public UsagePeriod withPlanCode(PlanCode newPlanCode, Instant now) {
    return new UsagePeriod(
        id,
        userId,
        Objects.requireNonNull(newPlanCode, "newPlanCode"),
        periodStart,
        periodEnd,
        reservedMinutes,
        processedMinutes,
        storedBytes,
        renders,
        transcriptionMinutes,
        multimodalMinutes,
        llmTokens,
        cpuSeconds,
        gpuSeconds,
        bandwidthBytes,
        estimatedCost,
        createdAt,
        now);
  }

  public UsagePeriod withAdditionalMetrics(
      long additionalStorageBytes,
      long additionalRenders,
      BigDecimal additionalTranscriptionMinutes,
      BigDecimal additionalMultimodalMinutes,
      long additionalLlmTokens,
      BigDecimal additionalCpuSeconds,
      BigDecimal additionalGpuSeconds,
      long additionalBandwidthBytes,
      BigDecimal additionalCost,
      Instant now) {
    return new UsagePeriod(
        id,
        userId,
        planCode,
        periodStart,
        periodEnd,
        reservedMinutes,
        processedMinutes,
        Math.addExact(storedBytes, additionalStorageBytes),
        Math.addExact(renders, additionalRenders),
        transcriptionMinutes.add(additionalTranscriptionMinutes),
        multimodalMinutes.add(additionalMultimodalMinutes),
        Math.addExact(llmTokens, additionalLlmTokens),
        cpuSeconds.add(additionalCpuSeconds),
        gpuSeconds.add(additionalGpuSeconds),
        Math.addExact(bandwidthBytes, additionalBandwidthBytes),
        estimatedCost.add(additionalCost),
        createdAt,
        now);
  }
}
