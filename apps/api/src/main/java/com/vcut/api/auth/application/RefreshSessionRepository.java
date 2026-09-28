package com.vcut.api.auth.application;

import com.vcut.api.auth.domain.RefreshSession;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshSessionRepository {

  Optional<RefreshSession> findByTokenHash(String tokenHash);

  RefreshSession save(RefreshSession session);

  void revoke(UUID sessionId, UUID replacementSessionId, Instant revokedAt);

  void revokeAllForUser(UUID userId, Instant revokedAt);
}
