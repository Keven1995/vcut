package com.vcut.api.clip.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record ClipScore(
    BigDecimal hook,
    BigDecimal context,
    BigDecimal development,
    BigDecimal payoff,
    BigDecimal independence,
    BigDecimal engagement) {

  public ClipScore {
    validate(hook, "hook");
    validate(context, "context");
    validate(development, "development");
    validate(payoff, "payoff");
    validate(independence, "independence");
    validate(engagement, "engagement");
  }

  public static ClipScore from(ClipCandidate candidate) {
    Objects.requireNonNull(candidate, "candidate");
    return new ClipScore(
        candidate.hookScore(),
        candidate.contextScore(),
        candidate.developmentScore(),
        candidate.payoffScore(),
        candidate.independenceScore(),
        candidate.engagementScore());
  }

  private static void validate(BigDecimal value, String field) {
    Objects.requireNonNull(value, field);
    if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException(field + " must be between 0 and 1");
    }
  }
}
