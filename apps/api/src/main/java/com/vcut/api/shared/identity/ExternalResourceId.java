package com.vcut.api.shared.identity;

import java.util.Objects;
import java.util.UUID;

public record ExternalResourceId(UUID value) {

  public ExternalResourceId {
    Objects.requireNonNull(value, "value is required");
  }

  public static ExternalResourceId generate() {
    return new ExternalResourceId(UUID.randomUUID());
  }
}
