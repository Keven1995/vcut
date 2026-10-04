package com.vcut.api.security.audit.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SecurityAuditEvent(
    UUID id,
    String eventType,
    UUID actorUserId,
    String routeTemplate,
    SecurityAuditOutcome outcome,
    int httpStatus,
    UUID correlationId,
    Instant occurredAt,
    Instant expiresAt) {

  public SecurityAuditEvent {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(expiresAt, "expiresAt");
    if (eventType == null || eventType.isBlank() || eventType.length() > 64) {
      throw new IllegalArgumentException("eventType must contain 1-64 characters");
    }
    if (routeTemplate == null || routeTemplate.isBlank() || routeTemplate.length() > 255) {
      throw new IllegalArgumentException("routeTemplate must contain 1-255 characters");
    }
    if (httpStatus < 100 || httpStatus > 599 || !expiresAt.isAfter(occurredAt)) {
      throw new IllegalArgumentException("audit status or retention period is invalid");
    }
  }
}
