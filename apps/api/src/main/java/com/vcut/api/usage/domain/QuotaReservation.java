package com.vcut.api.usage.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record QuotaReservation(
    UUID id,
    UUID periodId,
    UUID userId,
    UUID resourceId,
    String operation,
    String idempotencyKey,
    BigDecimal reservedMinutes,
    QuotaReservationStatus status,
    Instant createdAt,
    Instant updatedAt) {

  public QuotaReservation {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(periodId, "periodId");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(resourceId, "resourceId");
    if (operation == null
        || operation.isBlank()
        || idempotencyKey == null
        || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("reservation operation and idempotency key are required");
    }
    Objects.requireNonNull(reservedMinutes, "reservedMinutes");
    if (reservedMinutes.signum() <= 0) {
      throw new IllegalArgumentException("reservedMinutes must be positive");
    }
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  public QuotaReservation confirm(Instant now) {
    if (status != QuotaReservationStatus.RESERVED) {
      throw new IllegalStateException("only reserved quota can be confirmed");
    }
    return new QuotaReservation(
        id,
        periodId,
        userId,
        resourceId,
        operation,
        idempotencyKey,
        reservedMinutes,
        QuotaReservationStatus.CONFIRMED,
        createdAt,
        now);
  }

  public QuotaReservation release(Instant now) {
    if (status != QuotaReservationStatus.RESERVED) {
      throw new IllegalStateException("only reserved quota can be released");
    }
    return new QuotaReservation(
        id,
        periodId,
        userId,
        resourceId,
        operation,
        idempotencyKey,
        reservedMinutes,
        QuotaReservationStatus.RELEASED,
        createdAt,
        now);
  }
}
