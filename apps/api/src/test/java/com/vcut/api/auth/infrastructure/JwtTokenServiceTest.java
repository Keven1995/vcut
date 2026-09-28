package com.vcut.api.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtTokenServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-28T15:00:00Z");

  @Test
  void issuesAndParsesShortLivedAccessToken() {
    AuthProperties properties =
        new AuthProperties(
            "test-secret-with-at-least-32-characters-long",
            Duration.ofMinutes(15),
            Duration.ofDays(30),
            false);
    JwtTokenService service = new JwtTokenService(properties, Clock.fixed(NOW, ZoneOffset.UTC));
    UUID userId = UUID.randomUUID();

    JwtTokenService.IssuedAccessToken issued = service.issue(userId, "person@example.com");
    JwtTokenService.AuthenticatedToken parsed = service.parse(issued.value());

    assertThat(parsed.userId()).isEqualTo(userId);
    assertThat(parsed.email()).isEqualTo("person@example.com");
    assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(900));
  }

  @Test
  void rejectsAnUnsafeSecretBeforeIssuingTokens() {
    AuthProperties properties =
        new AuthProperties("too-short", Duration.ofMinutes(15), Duration.ofDays(30), false);
    JwtTokenService service = new JwtTokenService(properties, Clock.systemUTC());

    assertThatThrownBy(() -> service.issue(UUID.randomUUID(), "person@example.com"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("JWT_SECRET must contain at least 32 characters");
  }
}
