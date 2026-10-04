package com.vcut.api.security.ratelimit.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record RateLimitBucket(
    RateLimitSubjectScope scope,
    String subjectHash,
    String operation,
    Instant windowStartedAt,
    int requestCount,
    int violationCount,
    Instant blockedUntil,
    Instant lastViolationAt,
    Instant updatedAt) {

  private static final int MAX_TRACKED_VIOLATIONS = 30;

  public RateLimitBucket {
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(windowStartedAt, "windowStartedAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (subjectHash == null || !subjectHash.matches("^[a-f0-9]{64}$")) {
      throw new IllegalArgumentException("subjectHash must be a SHA-256 digest");
    }
    if (operation == null || operation.isBlank() || operation.length() > 64) {
      throw new IllegalArgumentException("operation must contain 1-64 characters");
    }
    if (requestCount < 0 || violationCount < 0) {
      throw new IllegalArgumentException("rate-limit counters must not be negative");
    }
  }

  public static RateLimitBucket empty(
      RateLimitSubjectScope scope,
      String subjectHash,
      String operation,
      Instant windowStartedAt,
      Instant now) {
    return new RateLimitBucket(
        scope, subjectHash, operation, windowStartedAt, 0, 0, null, null, now);
  }

  public Attempt consume(
      Instant now,
      Instant currentWindowStart,
      int requestLimit,
      Duration initialBlock,
      Duration maximumBlock,
      Duration violationCooldown) {
    if (requestLimit < 1
        || initialBlock.isNegative()
        || initialBlock.isZero()
        || maximumBlock.compareTo(initialBlock) < 0
        || violationCooldown.isNegative()
        || violationCooldown.isZero()) {
      throw new IllegalArgumentException("rate-limit policy is invalid");
    }
    if (blockedUntil != null && blockedUntil.isAfter(now)) {
      return new Attempt(
          this, false, Math.max(1, Duration.between(now, blockedUntil).toSeconds() + 1));
    }

    boolean newWindow = currentWindowStart.isAfter(windowStartedAt);
    boolean strikeWindowExpired =
        lastViolationAt != null && !lastViolationAt.plus(violationCooldown).isAfter(now);
    int currentRequests = newWindow ? 0 : requestCount;
    int currentViolations = strikeWindowExpired ? 0 : violationCount;
    Instant currentLastViolation = strikeWindowExpired ? null : lastViolationAt;
    if (currentRequests < requestLimit) {
      return new Attempt(
          new RateLimitBucket(
              scope,
              subjectHash,
              operation,
              currentWindowStart,
              currentRequests + 1,
              currentViolations,
              null,
              currentLastViolation,
              now),
          true,
          0);
    }

    int nextViolations = Math.min(MAX_TRACKED_VIOLATIONS, currentViolations + 1);
    Duration blockDuration = blockDuration(initialBlock, maximumBlock, nextViolations);
    Instant nextBlockedUntil = now.plus(blockDuration);
    RateLimitBucket blocked =
        new RateLimitBucket(
            scope,
            subjectHash,
            operation,
            currentWindowStart,
            currentRequests,
            nextViolations,
            nextBlockedUntil,
            now,
            now);
    return new Attempt(blocked, false, blockDuration.toSeconds());
  }

  private static Duration blockDuration(
      Duration initialBlock, Duration maximumBlock, int violationCount) {
    long seconds = initialBlock.toSeconds();
    long maximumSeconds = maximumBlock.toSeconds();
    for (int attempt = 1; attempt < violationCount && seconds < maximumSeconds; attempt++) {
      seconds = seconds > maximumSeconds / 2 ? maximumSeconds : seconds * 2;
    }
    return Duration.ofSeconds(Math.min(seconds, maximumSeconds));
  }

  public record Attempt(RateLimitBucket bucket, boolean allowed, long retryAfterSeconds) {}
}
