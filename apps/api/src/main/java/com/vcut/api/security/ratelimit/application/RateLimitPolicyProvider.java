package com.vcut.api.security.ratelimit.application;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

public interface RateLimitPolicyProvider {

  boolean enabled();

  Duration window();

  Duration initialBlock();

  Duration maximumBlock();

  Duration violationCooldown();

  Duration counterRetention();

  Map<String, OperationLimit> operations();

  default Optional<OperationLimit> operationLimit(String operation) {
    return Optional.ofNullable(operations().get(operation));
  }

  record OperationLimit(int ipRequests, int userRequests) {
    public OperationLimit {
      if (ipRequests < 0 || userRequests < 0 || (ipRequests == 0 && userRequests == 0)) {
        throw new IllegalArgumentException("rate-limit request limits are invalid");
      }
    }
  }
}
