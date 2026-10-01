package com.vcut.api.job.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OutboxMessage(
    UUID id,
    String aggregateType,
    UUID aggregateId,
    String eventType,
    String routingKey,
    String payload,
    int attempt,
    Instant availableAt,
    Instant publishedAt,
    String lastError,
    Instant createdAt) {

  public OutboxMessage {
    Objects.requireNonNull(id, "id");
    requireText(aggregateType, "aggregateType");
    Objects.requireNonNull(aggregateId, "aggregateId");
    requireText(eventType, "eventType");
    requireText(routingKey, "routingKey");
    requireText(payload, "payload");
    if (attempt < 0) {
      throw new IllegalArgumentException("attempt must not be negative");
    }
    Objects.requireNonNull(availableAt, "availableAt");
    Objects.requireNonNull(createdAt, "createdAt");
  }

  public OutboxMessage incrementAttempt(String error, Instant now) {
    return new OutboxMessage(
        id,
        aggregateType,
        aggregateId,
        eventType,
        routingKey,
        payload,
        attempt + 1,
        now,
        publishedAt,
        error,
        createdAt);
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
