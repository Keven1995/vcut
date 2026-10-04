package com.vcut.api.video.infrastructure;

import com.vcut.api.video.application.ExternalVideoImporter;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vcut.external-import")
public record ExternalVideoImportProperties(
    boolean enabled,
    boolean fixtureAdapterEnabled,
    long maxFileSizeBytes,
    Duration maxDuration,
    Duration timeout,
    int maxAttempts,
    Duration retryBackoff) {

  public ExternalVideoImportProperties {
    if (maxFileSizeBytes <= 0
        || maxDuration == null
        || maxDuration.isNegative()
        || maxDuration.isZero()
        || timeout == null
        || timeout.isNegative()
        || timeout.isZero()
        || maxAttempts < 1
        || retryBackoff == null
        || retryBackoff.isNegative()) {
      throw new IllegalArgumentException("external import limits must be positive");
    }
  }

  public ExternalVideoImporter.ImportLimits limits() {
    return new ExternalVideoImporter.ImportLimits(maxFileSizeBytes, maxDuration, timeout);
  }
}
