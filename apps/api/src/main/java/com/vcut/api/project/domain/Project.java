package com.vcut.api.project.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Project(
    UUID id, UUID userId, String name, ProjectStatus status, Instant createdAt, Instant updatedAt) {

  public Project {
    Objects.requireNonNull(id, "id is required");
    Objects.requireNonNull(userId, "userId is required");
    Objects.requireNonNull(name, "name is required");
    Objects.requireNonNull(status, "status is required");
    Objects.requireNonNull(createdAt, "createdAt is required");
    Objects.requireNonNull(updatedAt, "updatedAt is required");
    if (name.isBlank() || name.length() > 120) {
      throw new IllegalArgumentException("project name must contain 1 to 120 characters");
    }
  }
}
