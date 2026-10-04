package com.vcut.api.usage.application;

import com.vcut.api.usage.domain.RetentionPolicy;
import java.math.BigDecimal;
import java.util.Objects;

public record PlanLimits(
    BigDecimal monthlyProcessingMinutes,
    long maxFileSizeBytes,
    long maxDurationSeconds,
    long maxStorageBytes,
    int maxConcurrentJobs,
    int maxWidth,
    int maxHeight,
    RetentionPolicy retentionPolicy) {

  public PlanLimits {
    Objects.requireNonNull(monthlyProcessingMinutes, "monthlyProcessingMinutes");
    Objects.requireNonNull(retentionPolicy, "retentionPolicy");
    if (monthlyProcessingMinutes.signum() <= 0
        || maxFileSizeBytes <= 0
        || maxDurationSeconds <= 0
        || maxStorageBytes <= 0
        || maxConcurrentJobs <= 0) {
      throw new IllegalArgumentException("plan limits must be positive");
    }
    if (maxWidth <= 0 || maxHeight <= 0) {
      throw new IllegalArgumentException("plan resolution limits must be positive");
    }
  }
}
