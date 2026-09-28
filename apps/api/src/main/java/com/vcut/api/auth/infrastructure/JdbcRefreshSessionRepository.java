package com.vcut.api.auth.infrastructure;

import com.vcut.api.auth.application.RefreshSessionRepository;
import com.vcut.api.auth.domain.RefreshSession;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRefreshSessionRepository implements RefreshSessionRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcRefreshSessionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<RefreshSession> findByTokenHash(String tokenHash) {
    return jdbcTemplate
        .query(
            "SELECT * FROM refresh_sessions WHERE token_hash = ?",
            JdbcRefreshSessionRepository::mapSession,
            tokenHash)
        .stream()
        .findFirst();
  }

  @Override
  public RefreshSession save(RefreshSession session) {
    jdbcTemplate.update(
        "INSERT INTO refresh_sessions "
            + "(id, user_id, token_hash, expires_at, revoked_at, replaced_by_session_id, created_at, last_used_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        session.id(),
        session.userId(),
        session.tokenHash(),
        Timestamp.from(session.expiresAt()),
        timestamp(session.revokedAt()),
        session.replacedBySessionId(),
        Timestamp.from(session.createdAt()),
        timestamp(session.lastUsedAt()));
    return session;
  }

  @Override
  public void revoke(UUID sessionId, UUID replacementSessionId, Instant revokedAt) {
    jdbcTemplate.update(
        "UPDATE refresh_sessions SET revoked_at = ?, replaced_by_session_id = ?, last_used_at = ? "
            + "WHERE id = ?",
        Timestamp.from(revokedAt),
        replacementSessionId,
        Timestamp.from(revokedAt),
        sessionId);
  }

  @Override
  public void revokeAllForUser(UUID userId, Instant revokedAt) {
    jdbcTemplate.update(
        "UPDATE refresh_sessions SET revoked_at = COALESCE(revoked_at, ?) "
            + "WHERE user_id = ? AND revoked_at IS NULL",
        Timestamp.from(revokedAt),
        userId);
  }

  private static RefreshSession mapSession(ResultSet resultSet, int rowNumber) throws SQLException {
    Timestamp revokedAt = resultSet.getTimestamp("revoked_at");
    Timestamp lastUsedAt = resultSet.getTimestamp("last_used_at");
    return new RefreshSession(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("user_id", UUID.class),
        resultSet.getString("token_hash"),
        resultSet.getTimestamp("expires_at").toInstant(),
        revokedAt == null ? null : revokedAt.toInstant(),
        resultSet.getObject("replaced_by_session_id", UUID.class),
        resultSet.getTimestamp("created_at").toInstant(),
        lastUsedAt == null ? null : lastUsedAt.toInstant());
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }
}
