package com.vcut.api.auth.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record User(
    UUID id,
    String email,
    String normalizedEmail,
    String passwordHash,
    UserStatus status,
    Instant createdAt,
    Instant updatedAt) {

  public User {
    Objects.requireNonNull(id, "id is required");
    Objects.requireNonNull(email, "email is required");
    Objects.requireNonNull(normalizedEmail, "normalizedEmail is required");
    Objects.requireNonNull(passwordHash, "passwordHash is required");
    Objects.requireNonNull(status, "status is required");
    Objects.requireNonNull(createdAt, "createdAt is required");
    Objects.requireNonNull(updatedAt, "updatedAt is required");
    if (email.isBlank() || normalizedEmail.isBlank() || passwordHash.isBlank()) {
      throw new IllegalArgumentException("user text fields must not be blank");
    }
  }

  public boolean canAuthenticate() {
    return status == UserStatus.ACTIVE;
  }
}
