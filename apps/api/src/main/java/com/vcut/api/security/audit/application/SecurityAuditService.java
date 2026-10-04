package com.vcut.api.security.audit.application;

import com.vcut.api.security.audit.domain.SecurityAuditEvent;
import com.vcut.api.security.audit.domain.SecurityAuditOutcome;
import com.vcut.api.security.audit.infrastructure.SecurityAuditProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SecurityAuditService {

  private final SecurityAuditRepository repository;
  private final SecurityAuditProperties properties;
  private final Clock clock;

  @Autowired
  public SecurityAuditService(
      SecurityAuditRepository repository, SecurityAuditProperties properties) {
    this(repository, properties, Clock.systemUTC());
  }

  SecurityAuditService(
      SecurityAuditRepository repository, SecurityAuditProperties properties, Clock clock) {
    this.repository = repository;
    this.properties = properties;
    this.clock = clock;
  }

  @Transactional
  public void record(
      String eventType,
      UUID actorUserId,
      String routeTemplate,
      SecurityAuditOutcome outcome,
      int httpStatus,
      UUID correlationId) {
    if (!properties.enabled()) {
      return;
    }
    Instant now = clock.instant();
    repository.save(
        new SecurityAuditEvent(
            UUID.randomUUID(),
            eventType,
            actorUserId,
            routeTemplate,
            outcome,
            httpStatus,
            correlationId,
            now,
            now.plus(properties.retentionPeriod())));
  }

  @Scheduled(fixedDelayString = "${vcut.security.audit.cleanup-delay-ms:86400000}")
  @Transactional
  public void deleteExpired() {
    if (properties.enabled()) {
      repository.deleteExpiredBefore(clock.instant());
    }
  }
}
