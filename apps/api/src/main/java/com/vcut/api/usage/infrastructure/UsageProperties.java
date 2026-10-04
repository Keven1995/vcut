package com.vcut.api.usage.infrastructure;

import com.vcut.api.usage.application.PlanLimits;
import com.vcut.api.usage.domain.PlanCode;
import com.vcut.api.usage.domain.RetentionPolicy;
import com.vcut.api.usage.domain.UsageCostRates;
import java.math.BigDecimal;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vcut.usage")
public record UsageProperties(PlanSettings free, PlanSettings pro, CostSettings costs) {

  public UsageProperties {
    Objects.requireNonNull(free, "free");
    Objects.requireNonNull(pro, "pro");
    Objects.requireNonNull(costs, "costs");
  }

  public PlanLimits limitsFor(PlanCode planCode) {
    PlanSettings settings = planCode == PlanCode.PRO ? pro : free;
    return new PlanLimits(
        BigDecimal.valueOf(settings.monthlyProcessingMinutes()),
        settings.maxFileSizeBytes(),
        settings.maxDurationSeconds(),
        settings.maxStorageBytes(),
        settings.maxConcurrentJobs(),
        settings.maxWidth(),
        settings.maxHeight(),
        new RetentionPolicy(
            settings.retention().originalDays(),
            settings.retention().audioDays(),
            settings.retention().framesDays(),
            settings.retention().previewDays(),
            settings.retention().finalDays(),
            settings.retention().thumbnailDays(),
            settings.retention().failedJobArtifactDays()));
  }

  public UsageCostRates costRates() {
    return new UsageCostRates(
        costs.transcriptionPerMinute(),
        costs.multimodalPerMinute(),
        costs.llmPerThousandTokens(),
        costs.cpuPerSecond(),
        costs.gpuPerSecond(),
        costs.storagePerGiBMonth(),
        costs.bandwidthPerGiB());
  }

  public record PlanSettings(
      long monthlyProcessingMinutes,
      long maxFileSizeBytes,
      long maxDurationSeconds,
      long maxStorageBytes,
      int maxConcurrentJobs,
      int maxWidth,
      int maxHeight,
      RetentionSettings retention) {
    public PlanSettings {
      Objects.requireNonNull(retention, "retention");
      if (monthlyProcessingMinutes <= 0
          || maxFileSizeBytes <= 0
          || maxDurationSeconds <= 0
          || maxStorageBytes <= 0
          || maxConcurrentJobs <= 0
          || maxWidth <= 0
          || maxHeight <= 0) {
        throw new IllegalArgumentException("usage plan limits must be positive");
      }
    }
  }

  public record RetentionSettings(
      long originalDays,
      long audioDays,
      long framesDays,
      long previewDays,
      long finalDays,
      long thumbnailDays,
      long failedJobArtifactDays) {
    public RetentionSettings {
      if (originalDays < 0
          || audioDays < 0
          || framesDays < 0
          || previewDays < 0
          || finalDays < 0
          || thumbnailDays < 0
          || failedJobArtifactDays < 0) {
        throw new IllegalArgumentException("retention periods must not be negative");
      }
    }
  }

  public record CostSettings(
      BigDecimal transcriptionPerMinute,
      BigDecimal multimodalPerMinute,
      BigDecimal llmPerThousandTokens,
      BigDecimal cpuPerSecond,
      BigDecimal gpuPerSecond,
      BigDecimal storagePerGiBMonth,
      BigDecimal bandwidthPerGiB) {
    public CostSettings {
      Objects.requireNonNull(transcriptionPerMinute, "transcriptionPerMinute");
      Objects.requireNonNull(multimodalPerMinute, "multimodalPerMinute");
      Objects.requireNonNull(llmPerThousandTokens, "llmPerThousandTokens");
      Objects.requireNonNull(cpuPerSecond, "cpuPerSecond");
      Objects.requireNonNull(gpuPerSecond, "gpuPerSecond");
      Objects.requireNonNull(storagePerGiBMonth, "storagePerGiBMonth");
      Objects.requireNonNull(bandwidthPerGiB, "bandwidthPerGiB");
    }
  }
}
