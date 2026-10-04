package com.vcut.api.subscription.domain;

import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record BillingLedgerEntry(
    UUID id,
    UUID userId,
    UUID subscriptionId,
    UUID billingEventId,
    BillingLedgerType type,
    long amountMinorUnits,
    Currency currency,
    Instant createdAt) {

  public BillingLedgerEntry {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(subscriptionId, "subscriptionId");
    Objects.requireNonNull(billingEventId, "billingEventId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(createdAt, "createdAt");
    if (amountMinorUnits > 0 && type != BillingLedgerType.CHARGE) {
      throw new IllegalArgumentException("refund and chargeback entries must be non-positive");
    }
    if (amountMinorUnits < 0 && type == BillingLedgerType.CHARGE) {
      throw new IllegalArgumentException("charge entries must be non-negative");
    }
  }
}
