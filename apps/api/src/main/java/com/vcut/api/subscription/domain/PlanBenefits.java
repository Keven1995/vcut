package com.vcut.api.subscription.domain;

import com.vcut.api.usage.domain.RetentionPolicy;
import java.math.BigDecimal;
import java.util.Objects;

public record PlanBenefits(
    BigDecimal monthlyProcessingMinutes,
    long maxFileSizeBytes,
    long maxDurationSeconds,
    long maxStorageBytes,
    int maxConcurrentJobs,
    int workerPriority,
    int maxWidth,
    int maxHeight,
    RetentionPolicy retentionPolicy) {

  public PlanBenefits {
    Objects.requireNonNull(monthlyProcessingMinutes, "monthlyProcessingMinutes");
    Objects.requireNonNull(retentionPolicy, "retentionPolicy");
    if (monthlyProcessingMinutes.signum() <= 0
        || maxFileSizeBytes <= 0
        || maxDurationSeconds <= 0
        || maxStorageBytes <= 0
        || maxConcurrentJobs <= 0
        || maxWidth <= 0
        || maxHeight <= 0
        || workerPriority < 0
        || workerPriority > 10) {
      throw new IllegalArgumentException("plan benefits must be positive");
    }
  }
}
