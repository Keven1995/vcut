package com.vcut.api.subscription.domain;

import com.vcut.api.usage.domain.PlanCode;
import java.util.Currency;
import java.util.Objects;

public record SubscriptionPlan(
    PlanCode code,
    String displayName,
    long monthlyPriceMinorUnits,
    Currency currency,
    PlanBenefits benefits) {

  public SubscriptionPlan {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(benefits, "benefits");
    if (displayName == null || displayName.isBlank() || displayName.length() > 80) {
      throw new IllegalArgumentException("plan display name must contain 1-80 characters");
    }
    if (monthlyPriceMinorUnits < 0 || (code == PlanCode.FREE && monthlyPriceMinorUnits != 0)) {
      throw new IllegalArgumentException("plan price must be non-negative and FREE must be zero");
    }
  }
}
