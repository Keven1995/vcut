package com.vcut.api.auth.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AccountDeletionRepository {

  Optional<Instant> findPendingDeleteAfter(UUID userId);

  void enqueue(UUID userId, Instant requestedAt, Instant deleteAfter);

  boolean cancelPending(UUID userId, Instant cancelledAt);
}
