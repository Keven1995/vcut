package com.vcut.api.usage.application;

import com.vcut.api.usage.domain.PlanCode;
import com.vcut.api.usage.domain.UsageLedgerEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record UsageSummary(
    PlanCode planCode,
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
    List<UsageLedgerEntry> recentLedger) {
  public UsageSummary {
    recentLedger = List.copyOf(recentLedger);
  }
}
