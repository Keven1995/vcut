package com.vcut.api.usage.presentation;

import com.vcut.api.usage.application.UsageSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record UsageSummaryResponse(
    String planCode,
    Instant periodStart,
    Instant periodEnd,
    BigDecimal processingLimitMinutes,
    BigDecimal reservedMinutes,
    BigDecimal processedMinutes,
    BigDecimal remainingMinutes,
    long storedBytes,
    long retainedBytes,
    long storageLimitBytes,
    long renders,
    BigDecimal estimatedCost,
    int activeJobs,
    int concurrentJobLimit,
    List<LedgerEntry> recentLedger) {

  public UsageSummaryResponse {
    recentLedger = List.copyOf(recentLedger);
  }

  public static UsageSummaryResponse from(UsageSummary summary) {
    return new UsageSummaryResponse(
        summary.planCode().name(),
        summary.periodStart(),
        summary.periodEnd(),
        summary.processingLimitMinutes(),
        summary.reservedMinutes(),
        summary.processedMinutes(),
        summary.remainingMinutes(),
        summary.storedBytes(),
        summary.retainedBytes(),
        summary.storageLimitBytes(),
        summary.renders(),
        summary.estimatedCost(),
        summary.activeJobs(),
        summary.concurrentJobLimit(),
        summary.recentLedger().stream()
            .map(
                entry ->
                    new LedgerEntry(
                        entry.operation(),
                        entry.processedMinutes(),
                        entry.renders(),
                        entry.estimatedCost(),
                        entry.outcome(),
                        entry.createdAt()))
            .toList());
  }

  public record LedgerEntry(
      String operation,
      BigDecimal processedMinutes,
      long renders,
      BigDecimal estimatedCost,
      String outcome,
      Instant createdAt) {}
}
