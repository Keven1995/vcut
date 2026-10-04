package com.vcut.api.subscription.application;

import com.vcut.api.subscription.domain.BillingLedgerEntry;
import com.vcut.api.subscription.domain.BillingReconciliation;
import com.vcut.api.subscription.domain.CheckoutSession;
import com.vcut.api.subscription.domain.PaidSubscription;
import com.vcut.api.subscription.domain.SubscriptionPlan;
import java.util.List;

public record SubscriptionOverview(
    boolean sandboxEnabled,
    List<SubscriptionPlan> plans,
    PaidSubscription currentSubscription,
    List<CheckoutSession> checkoutSessions,
    List<BillingLedgerEntry> recentPayments,
    BillingReconciliation reconciliation) {

  public SubscriptionOverview {
    plans = List.copyOf(plans);
    checkoutSessions = List.copyOf(checkoutSessions);
    recentPayments = List.copyOf(recentPayments);
  }
}
