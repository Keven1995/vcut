package com.vcut.api.usage.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Plan assignment snapshot; payment lifecycle is intentionally owned by a later sprint. */
public record Subscription(
    UUID id,
    UUID userId,
    PlanCode planCode,
    SubscriptionStatus status,
    Instant periodStart,
    Instant periodEnd,
    Instant createdAt,
    Instant updatedAt) {

  public Subscription {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(planCode, "planCode");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(periodStart, "periodStart");
    Objects.requireNonNull(periodEnd, "periodEnd");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (!periodEnd.isAfter(periodStart)) {
      throw new IllegalArgumentException("subscription period must have positive duration");
    }
  }

  public boolean isActiveAt(Instant instant) {
    return status == SubscriptionStatus.ACTIVE
        && !instant.isBefore(periodStart)
        && instant.isBefore(periodEnd);
  }
}
