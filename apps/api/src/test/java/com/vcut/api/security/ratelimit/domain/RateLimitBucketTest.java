package com.vcut.api.security.ratelimit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RateLimitBucketTest {

  @Test
  void escalatesTemporaryBlocksAndResetsAfterTheViolationCooldown() {
    Instant now = Instant.parse("2026-10-04T12:00:00Z");
    Instant windowStart = Instant.parse("2026-10-04T12:00:00Z");
    RateLimitBucket bucket =
        RateLimitBucket.empty(
            RateLimitSubjectScope.IP, "a".repeat(64), "video-process", windowStart, now);

    RateLimitBucket.Attempt first = consume(bucket, now, windowStart);
    RateLimitBucket.Attempt second = consume(first.bucket(), now.plusSeconds(1), windowStart);
    RateLimitBucket.Attempt firstViolation =
        consume(second.bucket(), now.plusSeconds(2), windowStart);
    RateLimitBucket.Attempt secondViolation =
        consume(firstViolation.bucket(), now.plusSeconds(13), windowStart);
    RateLimitBucket.Attempt afterCooldown =
        consume(
            secondViolation.bucket(), now.plus(Duration.ofHours(2)), now.plus(Duration.ofHours(2)));

    assertThat(first.allowed()).isTrue();
    assertThat(second.allowed()).isTrue();
    assertThat(firstViolation.allowed()).isFalse();
    assertThat(firstViolation.retryAfterSeconds()).isEqualTo(10);
    assertThat(secondViolation.allowed()).isFalse();
    assertThat(secondViolation.retryAfterSeconds()).isEqualTo(20);
    assertThat(afterCooldown.allowed()).isTrue();
    assertThat(afterCooldown.bucket().violationCount()).isZero();
  }

  private static RateLimitBucket.Attempt consume(
      RateLimitBucket bucket, Instant now, Instant windowStart) {
    return bucket.consume(
        now, windowStart, 2, Duration.ofSeconds(10), Duration.ofSeconds(40), Duration.ofHours(1));
  }
}
