package com.vcut.api.subscription.domain;

import com.vcut.api.usage.domain.PlanCode;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record PaidSubscription(
    UUID id,
    UUID userId,
    PlanCode planCode,
    SubscriptionStatus status,
    String provider,
    String providerCustomerId,
    String providerSubscriptionId,
    long monthlyPriceMinorUnits,
    Currency currency,
    SubscriptionPeriod period,
    boolean cancelAtPeriodEnd,
    Instant lastEventAt,
    Instant createdAt,
    Instant updatedAt) {

  public PaidSubscription {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(planCode, "planCode");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    requireText(provider, "provider");
    if (!"legacy".equals(provider)) {
      requireText(providerCustomerId, "providerCustomerId");
      requireText(providerSubscriptionId, "providerSubscriptionId");
    }
    if (monthlyPriceMinorUnits < 0) {
      throw new IllegalArgumentException("monthlyPriceMinorUnits must not be negative");
    }
    if (lastEventAt != null && lastEventAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("lastEventAt must not be before createdAt");
    }
  }

  public boolean isActiveAt(Instant instant) {
    return status == SubscriptionStatus.ACTIVE && period.contains(instant);
  }

  public PaidSubscription renewed(
      SubscriptionPeriod newPeriod,
      long newPriceMinorUnits,
      Currency newCurrency,
      Instant occurredAt) {
    if (status == SubscriptionStatus.CANCELED
        || status == SubscriptionStatus.CHARGEBACK
        || status == SubscriptionStatus.EXPIRED) {
      throw new IllegalStateException("terminal subscription cannot be renewed");
    }
    if (newPeriod.startsAt().isBefore(period.endsAt())) {
      throw new IllegalArgumentException("renewal period must not overlap the current period");
    }
    if (newPriceMinorUnits < 0) {
      throw new IllegalArgumentException("newPriceMinorUnits must not be negative");
    }
    return copy(
        SubscriptionStatus.ACTIVE,
        newPeriod,
        newPriceMinorUnits,
        Objects.requireNonNull(newCurrency, "newCurrency"),
        false,
        occurredAt,
        occurredAt);
  }

  public PaidSubscription paymentFailed(Instant occurredAt) {
    requireNonTerminal();
    return copy(
        SubscriptionStatus.PAST_DUE,
        period,
        monthlyPriceMinorUnits,
        currency,
        cancelAtPeriodEnd,
        occurredAt,
        occurredAt);
  }

  public PaidSubscription scheduleCancellation(Instant occurredAt) {
    requireNonTerminal();
    return copy(status, period, monthlyPriceMinorUnits, currency, true, occurredAt, occurredAt);
  }

  public PaidSubscription canceled(Instant occurredAt) {
    if (status == SubscriptionStatus.CHARGEBACK) {
      throw new IllegalStateException("charged-back subscription cannot be canceled");
    }
    if (status == SubscriptionStatus.CANCELED) {
      return this;
    }
    return copy(
        SubscriptionStatus.CANCELED,
        period,
        monthlyPriceMinorUnits,
        currency,
        false,
        occurredAt,
        occurredAt);
  }

  public PaidSubscription chargedBack(Instant occurredAt) {
    return copy(
        SubscriptionStatus.CHARGEBACK,
        period,
        monthlyPriceMinorUnits,
        currency,
        false,
        occurredAt,
        occurredAt);
  }

  public PaidSubscription expired(Instant now) {
    if (period.endsAt().isAfter(now)) {
      throw new IllegalStateException("subscription period has not ended");
    }
    if (status == SubscriptionStatus.CHARGEBACK || status == SubscriptionStatus.CANCELED) {
      return this;
    }
    return copy(
        SubscriptionStatus.EXPIRED,
        period,
        monthlyPriceMinorUnits,
        currency,
        false,
        lastEventAt,
        now);
  }

  public boolean acceptsEventAt(Instant occurredAt) {
    return lastEventAt == null || occurredAt.isAfter(lastEventAt);
  }

  private PaidSubscription copy(
      SubscriptionStatus newStatus,
      SubscriptionPeriod newPeriod,
      long newPriceMinorUnits,
      Currency newCurrency,
      boolean newCancelAtPeriodEnd,
      Instant newLastEventAt,
      Instant now) {
    return new PaidSubscription(
        id,
        userId,
        planCode,
        newStatus,
        provider,
        providerCustomerId,
        providerSubscriptionId,
        newPriceMinorUnits,
        newCurrency,
        newPeriod,
        newCancelAtPeriodEnd,
        newLastEventAt,
        createdAt,
        now);
  }

  private void requireNonTerminal() {
    if (status == SubscriptionStatus.CANCELED
        || status == SubscriptionStatus.CHARGEBACK
        || status == SubscriptionStatus.EXPIRED) {
      throw new IllegalStateException("terminal subscription cannot change state");
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank() || value.length() > 255) {
      throw new IllegalArgumentException(field + " must contain 1-255 characters");
    }
  }
}
