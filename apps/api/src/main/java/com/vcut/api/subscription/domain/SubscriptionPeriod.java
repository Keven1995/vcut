package com.vcut.api.subscription.domain;

import java.time.Instant;
import java.util.Objects;

public record SubscriptionPeriod(Instant startsAt, Instant endsAt) {

  public SubscriptionPeriod {
    Objects.requireNonNull(startsAt, "startsAt");
    Objects.requireNonNull(endsAt, "endsAt");
    if (!endsAt.isAfter(startsAt)) {
      throw new IllegalArgumentException("subscription period must have positive duration");
    }
  }

  public boolean contains(Instant instant) {
    return !instant.isBefore(startsAt) && instant.isBefore(endsAt);
  }
}
