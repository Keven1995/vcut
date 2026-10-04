package com.vcut.api.subscription.domain;

import com.vcut.api.usage.domain.PlanCode;
import java.net.URI;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record CheckoutSession(
    UUID id,
    UUID userId,
    PlanCode planCode,
    CheckoutStatus status,
    String provider,
    String providerSessionId,
    String providerCustomerId,
    String checkoutUrl,
    long amountMinorUnits,
    Currency currency,
    Instant expiresAt,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt) {

  public CheckoutSession {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(planCode, "planCode");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(expiresAt, "expiresAt");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    requireText(provider, "provider");
    if (amountMinorUnits < 0) {
      throw new IllegalArgumentException("checkout amount must not be negative");
    }
    if (status == CheckoutStatus.PENDING && completedAt != null) {
      throw new IllegalArgumentException("pending checkout must not have completedAt");
    }
    if (status == CheckoutStatus.COMPLETED && completedAt == null) {
      throw new IllegalArgumentException("completed checkout requires completedAt");
    }
    if (checkoutUrl != null) {
      URI uri = URI.create(checkoutUrl);
      if (!uri.isAbsolute()
          || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
        throw new IllegalArgumentException("checkoutUrl must be an absolute HTTP URL");
      }
    }
  }

  public CheckoutSession attachProviderDetails(
      String sessionId, String customerId, String url, Instant providerExpiresAt, Instant now) {
    if (status != CheckoutStatus.PENDING || providerSessionId != null) {
      throw new IllegalStateException("checkout provider details can only be attached once");
    }
    if (providerExpiresAt == null
        || !providerExpiresAt.isAfter(now)
        || providerExpiresAt.isAfter(expiresAt)) {
      throw new IllegalArgumentException(
          "provider checkout expiry is outside the requested window");
    }
    return new CheckoutSession(
        id,
        userId,
        planCode,
        status,
        provider,
        requiredText(sessionId, "providerSessionId"),
        requiredText(customerId, "providerCustomerId"),
        requiredText(url, "checkoutUrl"),
        amountMinorUnits,
        currency,
        providerExpiresAt,
        createdAt,
        now,
        null);
  }

  public CheckoutSession complete(Instant now) {
    if (status != CheckoutStatus.PENDING || !now.isBefore(expiresAt)) {
      throw new IllegalStateException("checkout is not pending or has expired");
    }
    return copy(CheckoutStatus.COMPLETED, now);
  }

  public CheckoutSession fail(Instant now) {
    if (status != CheckoutStatus.PENDING) {
      return this;
    }
    return copy(CheckoutStatus.FAILED, null, now);
  }

  public CheckoutSession expire(Instant now) {
    if (status != CheckoutStatus.PENDING || now.isBefore(expiresAt)) {
      throw new IllegalStateException("checkout is not due to expire");
    }
    return copy(CheckoutStatus.EXPIRED, null, now);
  }

  private CheckoutSession copy(CheckoutStatus newStatus, Instant completedAt) {
    return copy(newStatus, completedAt, completedAt == null ? updatedAt : completedAt);
  }

  private CheckoutSession copy(CheckoutStatus newStatus, Instant newCompletedAt, Instant now) {
    return new CheckoutSession(
        id,
        userId,
        planCode,
        newStatus,
        provider,
        providerSessionId,
        providerCustomerId,
        checkoutUrl,
        amountMinorUnits,
        currency,
        expiresAt,
        createdAt,
        now,
        newCompletedAt);
  }

  private static String requiredText(String value, String field) {
    requireText(value, field);
    return value;
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank() || value.length() > 512) {
      throw new IllegalArgumentException(field + " must contain 1-512 characters");
    }
  }
}
