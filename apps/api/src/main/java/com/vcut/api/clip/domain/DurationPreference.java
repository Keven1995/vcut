package com.vcut.api.clip.domain;

import java.math.BigDecimal;

public enum DurationPreference {
  AUTO(null),
  SHORT(BigDecimal.valueOf(15)),
  MEDIUM(BigDecimal.valueOf(30)),
  LONG(BigDecimal.valueOf(60)),
  CUSTOM(null);

  private final BigDecimal defaultDurationSeconds;

  DurationPreference(BigDecimal defaultDurationSeconds) {
    this.defaultDurationSeconds = defaultDurationSeconds;
  }

  public BigDecimal defaultDurationSeconds() {
    return defaultDurationSeconds;
  }
}
