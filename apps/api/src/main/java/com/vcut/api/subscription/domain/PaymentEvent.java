package com.vcut.api.subscription.domain;

import com.vcut.api.usage.domain.PlanCode;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;

/** Verified provider facts, stripped of raw payloads and payment instrument data. */
public record PaymentEvent(
    String providerEventId,
    int eventVersion,
    BillingEventType type,
    Instant occurredAt,
    String providerCheckoutId,
    String providerSubscriptionId,
    String providerCustomerId,
    PlanCode planCode,
    SubscriptionPeriod period,
    long amountMinorUnits,
    Currency currency,
    boolean cancelAtPeriodEnd) {

  public PaymentEvent {
    requireText(providerEventId, "providerEventId", 255);
    if (eventVersion < 1) {
      throw new IllegalArgumentException("eventVersion must be positive");
    }
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(currency, "currency");
    if (amountMinorUnits < 0) {
      throw new IllegalArgumentException("amount must not be negative");
    }
    switch (type) {
      case SUBSCRIPTION_CREATED -> {
        requireText(providerCheckoutId, "providerCheckoutId", 255);
        requireText(providerSubscriptionId, "providerSubscriptionId", 255);
        requireText(providerCustomerId, "providerCustomerId", 255);
        Objects.requireNonNull(planCode, "planCode");
        Objects.requireNonNull(period, "period");
      }
      case SUBSCRIPTION_RENEWED -> {
        requireText(providerSubscriptionId, "providerSubscriptionId", 255);
        requireText(providerCustomerId, "providerCustomerId", 255);
        Objects.requireNonNull(planCode, "planCode");
        Objects.requireNonNull(period, "period");
      }
      case PAYMENT_FAILED, SUBSCRIPTION_CANCELED, CHARGEBACK ->
          requireText(providerSubscriptionId, "providerSubscriptionId", 255);
    }
  }

  private static void requireText(String value, String field, int maxLength) {
    if (value == null || value.isBlank() || value.length() > maxLength) {
      throw new IllegalArgumentException(field + " must contain 1-" + maxLength + " characters");
    }
  }
}
