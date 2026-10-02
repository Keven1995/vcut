package com.vcut.api.shared.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageEnvelope(
    MessageKind kind,
    UUID eventId,
    String eventType,
    int eventVersion,
    UUID jobId,
    UUID resourceId,
    String operation,
    int version,
    UUID correlationId,
    int attempt,
    Instant occurredAt,
    Map<String, Object> data) {

  public MessageEnvelope {
    Objects.requireNonNull(kind, "kind is required");
    Objects.requireNonNull(eventId, "eventId is required");
    Objects.requireNonNull(jobId, "jobId is required");
    Objects.requireNonNull(resourceId, "resourceId is required");
    Objects.requireNonNull(correlationId, "correlationId is required");
    Objects.requireNonNull(occurredAt, "occurredAt is required");
    Objects.requireNonNull(data, "data is required");
    requireText(eventType, "eventType");
    requireText(operation, "operation");
    requirePositive(eventVersion, "eventVersion");
    requirePositive(version, "version");
    requirePositive(attempt, "attempt");
    data = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(data));
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }

  private static void requirePositive(int value, String field) {
    if (value < 1) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }
}
