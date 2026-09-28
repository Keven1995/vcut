package com.vcut.api.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vcut.api.auth.domain.RefreshSession;
import com.vcut.api.auth.domain.User;
import com.vcut.api.auth.domain.UserStatus;
import com.vcut.api.auth.infrastructure.AuthProperties;
import com.vcut.api.auth.infrastructure.JwtTokenService;
import com.vcut.api.shared.errors.UnauthorizedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthApplicationServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-28T15:00:00Z");

  @Mock private UserRepository userRepository;
  @Mock private RefreshSessionRepository refreshSessionRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private JwtTokenService jwtTokenService;

  private AuthApplicationService service;
  private AuthProperties properties;

  @BeforeEach
  void setUp() {
    properties =
        new AuthProperties(
            "test-secret-with-at-least-32-characters-long",
            java.time.Duration.ofMinutes(15),
            java.time.Duration.ofDays(30),
            false);
    service =
        new AuthApplicationService(
            userRepository,
            refreshSessionRepository,
            passwordEncoder,
            jwtTokenService,
            properties,
            new java.security.SecureRandom(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void registersWithNormalizedEmailAndArgonHash() {
    when(userRepository.findByNormalizedEmail("person@example.com")).thenReturn(Optional.empty());
    when(passwordEncoder.encode("strong-password")).thenReturn("argon2-hash");
    when(jwtTokenService.issue(any(UUID.class), anyString()))
        .thenReturn(new JwtTokenService.IssuedAccessToken("access-token", NOW.plusSeconds(900)));

    AuthResult result = service.register(" Person@Example.com ", "strong-password");

    assertThat(result.accessToken()).isEqualTo("access-token");
    assertThat(result.refreshToken()).isNotBlank();
    verify(userRepository).save(any(User.class));
    verify(passwordEncoder).encode("strong-password");
  }

  @Test
  void rejectsReuseOfRevokedRefreshTokenAndRevokesAllSessions() {
    UUID userId = UUID.randomUUID();
    User user = user(userId);
    RefreshSession revoked =
        new RefreshSession(
            UUID.randomUUID(),
            userId,
            "hash",
            NOW.plusSeconds(900),
            NOW.minusSeconds(1),
            UUID.randomUUID(),
            NOW.minusSeconds(60),
            NOW.minusSeconds(1));
    when(refreshSessionRepository.findByTokenHash(anyString())).thenReturn(Optional.of(revoked));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> service.refresh("reused-token"))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessage("Refresh token is expired or revoked.");

    verify(refreshSessionRepository).revokeAllForUser(userId, NOW);
    verify(refreshSessionRepository, never()).save(any(RefreshSession.class));
  }

  @Test
  void rotatesAnActiveRefreshTokenAndRevokesThePreviousSession() {
    UUID userId = UUID.randomUUID();
    User user = user(userId);
    RefreshSession current =
        new RefreshSession(
            UUID.randomUUID(),
            userId,
            "hash",
            NOW.plusSeconds(900),
            null,
            null,
            NOW.minusSeconds(60),
            null);
    RefreshSession replacement =
        new RefreshSession(
            UUID.randomUUID(),
            userId,
            "replacement-hash",
            NOW.plusSeconds(900),
            null,
            null,
            NOW,
            null);
    when(refreshSessionRepository.findByTokenHash(anyString()))
        .thenReturn(Optional.of(current), Optional.of(replacement));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(jwtTokenService.issue(any(UUID.class), anyString()))
        .thenReturn(new JwtTokenService.IssuedAccessToken("access-token", NOW.plusSeconds(900)));

    AuthResult result = service.refresh("refresh-token");

    assertThat(result.accessToken()).isEqualTo("access-token");
    verify(refreshSessionRepository).revoke(current.id(), replacement.id(), NOW);
  }

  private static User user(UUID id) {
    return new User(
        id, "person@example.com", "person@example.com", "hash", UserStatus.ACTIVE, NOW, NOW);
  }
}
