package com.vcut.api.subscription.domain;

import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record BillingEventRecord(
    UUID id,
    String provider,
    String providerEventId,
    int eventVersion,
    BillingEventType type,
    BillingEventStatus status,
    Instant occurredAt,
    UUID userId,
    UUID checkoutSessionId,
    UUID subscriptionId,
    long amountMinorUnits,
    Currency currency,
    Instant receivedAt,
    Instant processedAt) {

  public BillingEventRecord {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(receivedAt, "receivedAt");
    requireText(provider, "provider");
    requireText(providerEventId, "providerEventId");
    if (eventVersion < 1) {
      throw new IllegalArgumentException("eventVersion must be positive");
    }
    if (status == BillingEventStatus.PROCESSING && processedAt != null) {
      throw new IllegalArgumentException("processing event must not have processedAt");
    }
    if (status != BillingEventStatus.PROCESSING && processedAt == null) {
      throw new IllegalArgumentException("completed event requires processedAt");
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank() || value.length() > 255) {
      throw new IllegalArgumentException(field + " must contain 1-255 characters");
    }
  }
}
