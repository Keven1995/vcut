package com.vcut.api.security.audit.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.vcut.api.security.audit.domain.SecurityAuditOutcome;
import com.vcut.api.security.audit.infrastructure.SecurityAuditProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecurityAuditServiceTest {

  @Test
  void storesOnlyRouteTemplateAndRetentionBoundedSecurityMetadata() {
    Instant now = Instant.parse("2026-10-04T12:00:00Z");
    SecurityAuditRepository repository = mock(SecurityAuditRepository.class);
    SecurityAuditService service =
        new SecurityAuditService(
            repository, new SecurityAuditProperties(true, 365), Clock.fixed(now, ZoneOffset.UTC));
    UUID userId = UUID.randomUUID();

    service.record(
        "VIDEO_DELETE",
        userId,
        "/api/videos/{videoId}",
        SecurityAuditOutcome.SUCCESS,
        204,
        UUID.randomUUID());

    var event =
        org.mockito.ArgumentCaptor.forClass(
            com.vcut.api.security.audit.domain.SecurityAuditEvent.class);
    verify(repository).save(event.capture());
    org.assertj.core.api.Assertions.assertThat(event.getValue().actorUserId()).isEqualTo(userId);
    org.assertj.core.api.Assertions.assertThat(event.getValue().routeTemplate())
        .isEqualTo("/api/videos/{videoId}");
    org.assertj.core.api.Assertions.assertThat(event.getValue().expiresAt())
        .isEqualTo(now.plus(Duration.ofDays(365)));
  }
}
