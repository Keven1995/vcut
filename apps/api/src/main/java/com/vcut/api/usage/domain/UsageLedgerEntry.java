package com.vcut.api.usage.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record UsageLedgerEntry(
    UUID id,
    UUID periodId,
    UUID userId,
    UUID resourceId,
    String operation,
    String idempotencyKey,
    BigDecimal reservedMinutes,
    BigDecimal processedMinutes,
    long storageBytes,
    long renders,
    BigDecimal transcriptionMinutes,
    BigDecimal multimodalMinutes,
    long llmTokens,
    BigDecimal cpuSeconds,
    BigDecimal gpuSeconds,
    long bandwidthBytes,
    BigDecimal estimatedCost,
    String outcome,
    Instant createdAt) {

  public UsageLedgerEntry {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(periodId, "periodId");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(resourceId, "resourceId");
    Objects.requireNonNull(reservedMinutes, "reservedMinutes");
    Objects.requireNonNull(processedMinutes, "processedMinutes");
    Objects.requireNonNull(transcriptionMinutes, "transcriptionMinutes");
    Objects.requireNonNull(multimodalMinutes, "multimodalMinutes");
    Objects.requireNonNull(cpuSeconds, "cpuSeconds");
    Objects.requireNonNull(gpuSeconds, "gpuSeconds");
    Objects.requireNonNull(estimatedCost, "estimatedCost");
    if (operation == null
        || operation.isBlank()
        || idempotencyKey == null
        || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("ledger operation and idempotency key are required");
    }
    if (reservedMinutes.signum() < 0
        || processedMinutes.signum() < 0
        || storageBytes < 0
        || renders < 0
        || transcriptionMinutes.signum() < 0
        || multimodalMinutes.signum() < 0
        || llmTokens < 0
        || cpuSeconds.signum() < 0
        || gpuSeconds.signum() < 0
        || bandwidthBytes < 0
        || estimatedCost.signum() < 0) {
      throw new IllegalArgumentException("ledger metrics must not be negative");
    }
    if (!"SUCCEEDED".equals(outcome) && !"FAILED".equals(outcome) && !"RELEASED".equals(outcome)) {
      throw new IllegalArgumentException("unsupported usage ledger outcome");
    }
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
