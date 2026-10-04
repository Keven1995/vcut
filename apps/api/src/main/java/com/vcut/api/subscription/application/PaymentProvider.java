package com.vcut.api.subscription.application;

import com.vcut.api.subscription.domain.PaymentEvent;
import com.vcut.api.usage.domain.PlanCode;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

public interface PaymentProvider {

  String providerCode();

  ProviderCheckout createCheckout(
      UUID checkoutSessionId,
      String existingProviderCustomerId,
      PlanCode planCode,
      long amountMinorUnits,
      Currency currency,
      Instant expiresAt);

  ProviderCancellation requestCancellation(String providerSubscriptionId, Instant periodEnd);

  PaymentEvent verifyWebhook(String timestamp, String signature, String rawBody);

  record ProviderCheckout(
      String providerSessionId, String providerCustomerId, String checkoutUrl, Instant expiresAt) {}

  record ProviderCancellation(boolean accepted, boolean atPeriodEnd, Instant effectiveAt) {}
}
