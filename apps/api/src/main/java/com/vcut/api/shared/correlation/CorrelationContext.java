package com.vcut.api.shared.correlation;

import java.util.Optional;
import java.util.UUID;

public final class CorrelationContext {

  private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

  private CorrelationContext() {}

  public static void set(UUID correlationId) {
    CURRENT.set(correlationId);
  }

  public static Optional<UUID> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  public static UUID require() {
    return current()
        .orElseThrow(() -> new IllegalStateException("correlation ID is not available"));
  }

  public static void clear() {
    CURRENT.remove();
  }
}
