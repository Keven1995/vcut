package com.vcut.api.security.ratelimit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vcut.api.security.ratelimit.domain.RateLimitBucket;
import com.vcut.api.security.ratelimit.domain.RateLimitSubjectScope;
import com.vcut.api.security.ratelimit.infrastructure.RateLimitProperties;
import com.vcut.api.shared.errors.RateLimitExceededException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RateLimitApplicationServiceTest {

  @Test
  void appliesBothIpAndUserLimitsAndPersistsProgressiveBlockState() {
    Instant now = Instant.parse("2026-10-04T12:00:00Z");
    InMemoryRateLimitRepository repository = new InMemoryRateLimitRepository();
    RateLimitPolicyProvider properties =
        new RateLimitProperties(
            true,
            Duration.ofMinutes(1),
            Duration.ofSeconds(10),
            Duration.ofMinutes(2),
            Duration.ofHours(1),
            Duration.ofDays(1),
            Map.of("video-process", new RateLimitPolicyProvider.OperationLimit(5, 2)));
    RateLimitSubjectHasher hasher = RateLimitApplicationServiceTest::hashSubject;
    RateLimitApplicationService service =
        new RateLimitApplicationService(
            repository, hasher, properties, Clock.fixed(now, ZoneOffset.UTC));
    UUID userId = UUID.randomUUID();

    service.enforce("video-process", "192.0.2.7", userId);
    service.enforce("video-process", "192.0.2.7", userId);

    assertThatThrownBy(() -> service.enforce("video-process", "192.0.2.7", userId))
        .isInstanceOf(RateLimitExceededException.class)
        .hasMessageContaining("Retry after 10 seconds");
    RateLimitBucket userBucket =
        repository.find(
            RateLimitSubjectScope.USER, hashSubject(RateLimitSubjectScope.USER, userId.toString()));
    RateLimitBucket ipBucket =
        repository.find(
            RateLimitSubjectScope.IP, hashSubject(RateLimitSubjectScope.IP, "192.0.2.7"));

    assertThat(userBucket.requestCount()).isEqualTo(2);
    assertThat(userBucket.violationCount()).isEqualTo(1);
    assertThat(ipBucket.requestCount()).isEqualTo(3);
    assertThat(userBucket.subjectHash()).hasSize(64).doesNotContain(userId.toString());
    assertThat(ipBucket.subjectHash()).doesNotContain("192.0.2.7");
  }

  private static String hashSubject(RateLimitSubjectScope scope, String subject) {
    try {
      byte[] value =
          MessageDigest.getInstance("SHA-256")
              .digest((scope + ":" + subject).getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(value);
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static final class InMemoryRateLimitRepository implements RateLimitRepository {
    private final Map<Key, RateLimitBucket> buckets = new HashMap<>();

    @Override
    public RateLimitBucket lockOrCreate(RateLimitBucket initial) {
      Key key = key(initial);
      return buckets.computeIfAbsent(key, ignored -> initial);
    }

    @Override
    public void update(RateLimitBucket bucket) {
      buckets.put(key(bucket), bucket);
    }

    @Override
    public int deleteUpdatedBefore(Instant cutoff) {
      int before = buckets.size();
      buckets.values().removeIf(bucket -> bucket.updatedAt().isBefore(cutoff));
      return before - buckets.size();
    }

    private RateLimitBucket find(RateLimitSubjectScope scope, String subjectHash) {
      return buckets.values().stream()
          .filter(bucket -> bucket.scope() == scope && bucket.subjectHash().equals(subjectHash))
          .findFirst()
          .orElseThrow();
    }

    private static Key key(RateLimitBucket bucket) {
      return new Key(bucket.scope(), bucket.subjectHash(), bucket.operation());
    }

    private record Key(RateLimitSubjectScope scope, String subjectHash, String operation) {}
  }
}
