package com.vcut.api.security.ratelimit.infrastructure;

import com.vcut.api.security.ratelimit.application.RateLimitPolicyProvider;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vcut.security.rate-limit")
public record RateLimitProperties(
    boolean enabled,
    Duration window,
    Duration initialBlock,
    Duration maximumBlock,
    Duration violationCooldown,
    Duration counterRetention,
    Map<String, RateLimitPolicyProvider.OperationLimit> operations)
    implements RateLimitPolicyProvider {

  public RateLimitProperties {
    Objects.requireNonNull(window, "window");
    Objects.requireNonNull(initialBlock, "initialBlock");
    Objects.requireNonNull(maximumBlock, "maximumBlock");
    Objects.requireNonNull(violationCooldown, "violationCooldown");
    Objects.requireNonNull(counterRetention, "counterRetention");
    operations = Map.copyOf(operations);
    if (window.compareTo(Duration.ofSeconds(1)) < 0
        || initialBlock.isNegative()
        || initialBlock.isZero()
        || maximumBlock.compareTo(initialBlock) < 0
        || violationCooldown.isNegative()
        || violationCooldown.isZero()
        || counterRetention.isNegative()
        || counterRetention.isZero()) {
      throw new IllegalArgumentException("rate-limit durations are invalid");
    }
    for (String operation : operations.keySet()) {
      if (operation.isBlank() || operation.length() > 64) {
        throw new IllegalArgumentException(
            "rate-limit operation keys must contain 1-64 characters");
      }
    }
  }
}
