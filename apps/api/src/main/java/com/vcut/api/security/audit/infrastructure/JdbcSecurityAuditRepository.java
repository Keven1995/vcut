package com.vcut.api.security.audit.infrastructure;

import com.vcut.api.security.audit.application.SecurityAuditRepository;
import com.vcut.api.security.audit.domain.SecurityAuditEvent;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSecurityAuditRepository implements SecurityAuditRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcSecurityAuditRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void save(SecurityAuditEvent event) {
    jdbcTemplate.update(
        "INSERT INTO security_audit_events (id, event_type, actor_user_id, route_template, "
            + "outcome, http_status, correlation_id, occurred_at, expires_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        event.id(),
        event.eventType(),
        event.actorUserId(),
        event.routeTemplate(),
        event.outcome().name(),
        event.httpStatus(),
        event.correlationId(),
        Timestamp.from(event.occurredAt()),
        Timestamp.from(event.expiresAt()));
  }

  @Override
  public int deleteExpiredBefore(Instant cutoff) {
    return jdbcTemplate.update(
        "DELETE FROM security_audit_events WHERE expires_at <= ?", Timestamp.from(cutoff));
  }
}
