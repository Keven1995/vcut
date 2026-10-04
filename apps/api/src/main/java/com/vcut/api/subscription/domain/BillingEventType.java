package com.vcut.api.subscription.domain;

public enum BillingEventType {
  SUBSCRIPTION_CREATED,
  SUBSCRIPTION_RENEWED,
  PAYMENT_FAILED,
  SUBSCRIPTION_CANCELED,
  CHARGEBACK
}
