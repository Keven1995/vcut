package com.vcut.api.subscription.application;

import com.vcut.api.usage.domain.PlanCode;
import java.time.Duration;
import java.util.Currency;

public interface SubscriptionPricingProvider {

  long monthlyPriceMinorUnits(PlanCode planCode);

  Currency currency();

  Duration checkoutTtl();
}
