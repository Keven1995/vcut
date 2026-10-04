package com.vcut.api.subscription.infrastructure;

import com.vcut.api.subscription.application.SubscriptionPricingProvider;
import com.vcut.api.usage.domain.PlanCode;
import java.time.Duration;
import java.util.Currency;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vcut.subscription")
public record SubscriptionProperties(
    long proMonthlyPriceMinorUnits, Currency currency, Duration checkoutTtl)
    implements SubscriptionPricingProvider {

  public SubscriptionProperties {
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(checkoutTtl, "checkoutTtl");
    if (proMonthlyPriceMinorUnits < 0 || checkoutTtl.isNegative() || checkoutTtl.isZero()) {
      throw new IllegalArgumentException("subscription price and checkout TTL are invalid");
    }
  }

  @Override
  public long monthlyPriceMinorUnits(PlanCode planCode) {
    return planCode == PlanCode.FREE ? 0 : proMonthlyPriceMinorUnits;
  }
}
