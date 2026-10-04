package com.vcut.api.subscription.application;

import com.vcut.api.subscription.domain.CheckoutSession;
import com.vcut.api.subscription.domain.PaidSubscription;

public interface SandboxPaymentSimulator {

  boolean enabled();

  SignedWebhook completeCheckout(CheckoutSession checkoutSession);

  SignedWebhook cancelAtPeriodEnd(PaidSubscription subscription);

  record SignedWebhook(String timestamp, String signature, String body) {}
}
