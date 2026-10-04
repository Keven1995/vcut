package com.vcut.api.subscription.application;

import com.vcut.api.subscription.domain.PlanBenefits;
import com.vcut.api.subscription.domain.SubscriptionPlan;
import com.vcut.api.usage.application.PlanLimits;
import com.vcut.api.usage.application.PlanLimitsProvider;
import com.vcut.api.usage.domain.PlanCode;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SubscriptionPlanCatalog {

  private final PlanLimitsProvider planLimitsProvider;
  private final SubscriptionPricingProvider pricingProvider;

  public SubscriptionPlanCatalog(
      PlanLimitsProvider planLimitsProvider, SubscriptionPricingProvider pricingProvider) {
    this.planLimitsProvider = planLimitsProvider;
    this.pricingProvider = pricingProvider;
  }

  public List<SubscriptionPlan> plans() {
    return List.of(planFor(PlanCode.FREE), planFor(PlanCode.PRO));
  }

  public SubscriptionPlan planFor(PlanCode code) {
    PlanLimits limits = planLimitsProvider.limitsFor(code);
    return new SubscriptionPlan(
        code,
        code == PlanCode.FREE ? "Free" : "Pro",
        pricingProvider.monthlyPriceMinorUnits(code),
        pricingProvider.currency(),
        new PlanBenefits(
            limits.monthlyProcessingMinutes(),
            limits.maxFileSizeBytes(),
            limits.maxDurationSeconds(),
            limits.maxStorageBytes(),
            limits.maxConcurrentJobs(),
            limits.workerPriority(),
            limits.maxWidth(),
            limits.maxHeight(),
            limits.retentionPolicy()));
  }
}
