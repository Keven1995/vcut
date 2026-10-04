package com.vcut.api.security.audit.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vcut.security.audit")
public record SecurityAuditProperties(boolean enabled, long retentionDays) {

  public SecurityAuditProperties {
    if (retentionDays < 1 || retentionDays > 3_650) {
      throw new IllegalArgumentException(
          "security audit retention must be between 1 and 3650 days");
    }
  }

  public Duration retentionPeriod() {
    return Duration.ofDays(retentionDays);
  }
}
