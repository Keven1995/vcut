package com.vcut.api.subscription.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.vcut.api.usage.application.PlanLimits;
import com.vcut.api.usage.application.PlanLimitsProvider;
import com.vcut.api.usage.domain.PlanCode;
import com.vcut.api.usage.domain.RetentionPolicy;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class SubscriptionPlanCatalogTest {

  @Test
  void buildsPlanOffersFromConfiguredUsageBenefitsAndPrices() {
    PlanLimitsProvider limitsProvider =
        code ->
            new PlanLimits(
                BigDecimal.valueOf(code == PlanCode.PRO ? 600 : 60),
                code == PlanCode.PRO ? 2_000 : 500,
                7_200,
                code == PlanCode.PRO ? 50_000 : 5_000,
                code == PlanCode.PRO ? 3 : 1,
                code == PlanCode.PRO ? 3_840 : 1_920,
                code == PlanCode.PRO ? 3_840 : 1_920,
                code == PlanCode.PRO ? 5 : 0,
                code == PlanCode.PRO
                    ? new RetentionPolicy(90, 30, 14, 30, 90, 90, 14)
                    : new RetentionPolicy(30, 7, 3, 7, 30, 30, 7));
    SubscriptionPricingProvider pricing =
        new SubscriptionPricingProvider() {
          @Override
          public long monthlyPriceMinorUnits(PlanCode planCode) {
            return planCode == PlanCode.PRO ? 1_200 : 0;
          }

          @Override
          public Currency currency() {
            return Currency.getInstance("USD");
          }

          @Override
          public Duration checkoutTtl() {
            return Duration.ofMinutes(30);
          }
        };

    var offers = new SubscriptionPlanCatalog(limitsProvider, pricing).plans();

    assertThat(offers)
        .extracting(offer -> offer.code())
        .containsExactly(PlanCode.FREE, PlanCode.PRO);
    assertThat(offers.get(0).monthlyPriceMinorUnits()).isZero();
    assertThat(offers.get(1).monthlyPriceMinorUnits()).isEqualTo(1_200);
    assertThat(offers.get(1).benefits().workerPriority()).isEqualTo(5);
    assertThat(offers.get(1).benefits().retentionPolicy().finalDays()).isEqualTo(90);
  }
}
