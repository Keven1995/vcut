package com.vcut.api.subscription.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PaymentCustomer(
    UUID id,
    UUID userId,
    String provider,
    String providerCustomerId,
    Instant createdAt,
    Instant updatedAt) {

  public PaymentCustomer {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    requireText(provider, "provider");
    requireText(providerCustomerId, "providerCustomerId");
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank() || value.length() > 255) {
      throw new IllegalArgumentException(field + " must contain 1-255 characters");
    }
  }
}
