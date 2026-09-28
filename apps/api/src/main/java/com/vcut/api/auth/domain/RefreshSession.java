package com.vcut.api.auth.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RefreshSession(
    UUID id,
    UUID userId,
    String tokenHash,
    Instant expiresAt,
    Instant revokedAt,
    UUID replacedBySessionId,
    Instant createdAt,
    Instant lastUsedAt) {

  public RefreshSession {
    Objects.requireNonNull(id, "id is required");
    Objects.requireNonNull(userId, "userId is required");
    Objects.requireNonNull(tokenHash, "tokenHash is required");
    Objects.requireNonNull(expiresAt, "expiresAt is required");
    Objects.requireNonNull(createdAt, "createdAt is required");
    if (tokenHash.isBlank()) {
      throw new IllegalArgumentException("tokenHash must not be blank");
    }
  }

  public boolean isActiveAt(Instant instant) {
    return revokedAt == null && expiresAt.isAfter(instant);
  }
}
