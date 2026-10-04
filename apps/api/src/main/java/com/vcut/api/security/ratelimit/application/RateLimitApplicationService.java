package com.vcut.api.security.ratelimit.application;

import com.vcut.api.security.ratelimit.domain.RateLimitBucket;
import com.vcut.api.security.ratelimit.domain.RateLimitSubjectScope;
import com.vcut.api.shared.errors.RateLimitExceededException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RateLimitApplicationService {

  private final RateLimitRepository repository;
  private final RateLimitSubjectHasher subjectHasher;
  private final RateLimitPolicyProvider policyProvider;
  private final Clock clock;

  @Autowired
  public RateLimitApplicationService(
      RateLimitRepository repository,
      RateLimitSubjectHasher subjectHasher,
      RateLimitPolicyProvider policyProvider) {
    this(repository, subjectHasher, policyProvider, Clock.systemUTC());
  }

  RateLimitApplicationService(
      RateLimitRepository repository,
      RateLimitSubjectHasher subjectHasher,
      RateLimitPolicyProvider policyProvider,
      Clock clock) {
    this.repository = repository;
    this.subjectHasher = subjectHasher;
    this.policyProvider = policyProvider;
    this.clock = clock;
  }

  @Transactional(noRollbackFor = RateLimitExceededException.class)
  public void enforce(String operation, String remoteAddress, UUID userId) {
    if (!policyProvider.enabled()) {
      return;
    }
    RateLimitPolicyProvider.OperationLimit limits =
        policyProvider
            .operationLimit(operation)
            .orElseThrow(() -> new IllegalArgumentException("unknown rate-limit operation"));
    Instant now = clock.instant();
    Instant windowStart = windowStart(now, policyProvider.window());
    if (remoteAddress != null && !remoteAddress.isBlank() && limits.ipRequests() > 0) {
      enforceSubject(
          operation,
          RateLimitSubjectScope.IP,
          remoteAddress,
          limits.ipRequests(),
          windowStart,
          now);
    }
    if (userId != null && limits.userRequests() > 0) {
      enforceSubject(
          operation,
          RateLimitSubjectScope.USER,
          userId.toString(),
          limits.userRequests(),
          windowStart,
          now);
    }
  }

  @Scheduled(fixedDelayString = "${vcut.security.rate-limit.cleanup-delay-ms:3600000}")
  @Transactional
  public void removeExpiredCounters() {
    if (policyProvider.enabled()) {
      repository.deleteUpdatedBefore(clock.instant().minus(policyProvider.counterRetention()));
    }
  }

  private void enforceSubject(
      String operation,
      RateLimitSubjectScope scope,
      String subject,
      int limit,
      Instant windowStart,
      Instant now) {
    String subjectHash = subjectHasher.hash(scope, subject);
    RateLimitBucket bucket =
        repository.lockOrCreate(
            RateLimitBucket.empty(scope, subjectHash, operation, windowStart, now));
    RateLimitBucket.Attempt attempt =
        bucket.consume(
            now,
            windowStart,
            limit,
            policyProvider.initialBlock(),
            policyProvider.maximumBlock(),
            policyProvider.violationCooldown());
    repository.update(attempt.bucket());
    if (!attempt.allowed()) {
      throw new RateLimitExceededException(
          "Too many requests. Retry after " + attempt.retryAfterSeconds() + " seconds.");
    }
  }

  private static Instant windowStart(Instant now, Duration window) {
    long seconds = window.toSeconds();
    return Instant.ofEpochSecond(now.getEpochSecond() / seconds * seconds);
  }
}
