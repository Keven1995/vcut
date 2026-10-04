package com.vcut.api.auth.application;

import com.vcut.api.auth.domain.RefreshSession;
import com.vcut.api.auth.domain.User;
import com.vcut.api.auth.domain.UserStatus;
import com.vcut.api.auth.infrastructure.AuthProperties;
import com.vcut.api.auth.infrastructure.JwtTokenService;
import com.vcut.api.auth.infrastructure.TokenHashing;
import com.vcut.api.shared.errors.ConflictException;
import com.vcut.api.shared.errors.UnauthorizedException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthApplicationService {

  private final UserRepository userRepository;
  private final RefreshSessionRepository refreshSessionRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenService jwtTokenService;
  private final AuthProperties authProperties;
  private final AccountDeletionApplicationService accountDeletionApplicationService;
  private final SecureRandom secureRandom;
  private final Clock clock;

  @Autowired
  public AuthApplicationService(
      UserRepository userRepository,
      RefreshSessionRepository refreshSessionRepository,
      PasswordEncoder passwordEncoder,
      JwtTokenService jwtTokenService,
      AuthProperties authProperties,
      AccountDeletionApplicationService accountDeletionApplicationService) {
    this(
        userRepository,
        refreshSessionRepository,
        passwordEncoder,
        jwtTokenService,
        authProperties,
        accountDeletionApplicationService,
        new SecureRandom(),
        Clock.systemUTC());
  }

  AuthApplicationService(
      UserRepository userRepository,
      RefreshSessionRepository refreshSessionRepository,
      PasswordEncoder passwordEncoder,
      JwtTokenService jwtTokenService,
      AuthProperties authProperties,
      SecureRandom secureRandom,
      Clock clock) {
    this(
        userRepository,
        refreshSessionRepository,
        passwordEncoder,
        jwtTokenService,
        authProperties,
        null,
        secureRandom,
        clock);
  }

  AuthApplicationService(
      UserRepository userRepository,
      RefreshSessionRepository refreshSessionRepository,
      PasswordEncoder passwordEncoder,
      JwtTokenService jwtTokenService,
      AuthProperties authProperties,
      AccountDeletionApplicationService accountDeletionApplicationService,
      SecureRandom secureRandom,
      Clock clock) {
    this.userRepository = userRepository;
    this.refreshSessionRepository = refreshSessionRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtTokenService = jwtTokenService;
    this.authProperties = authProperties;
    this.accountDeletionApplicationService = accountDeletionApplicationService;
    this.secureRandom = secureRandom;
    this.clock = clock;
  }

  @Transactional
  public AuthResult register(String email, String password) {
    String normalizedEmail = normalizeEmail(email);
    if (userRepository.findByNormalizedEmail(normalizedEmail).isPresent()) {
      throw new ConflictException("An account with this email already exists.");
    }

    Instant now = clock.instant();
    User user =
        new User(
            UUID.randomUUID(),
            email.trim(),
            normalizedEmail,
            passwordEncoder.encode(password),
            UserStatus.ACTIVE,
            now,
            now);
    userRepository.save(user);
    return issueSession(user);
  }

  @Transactional
  public AuthResult login(String email, String password) {
    User candidate =
        userRepository
            .findByNormalizedEmail(normalizeEmail(email))
            .filter(existingUser -> passwordEncoder.matches(password, existingUser.passwordHash()))
            .orElseThrow(() -> new UnauthorizedException("Invalid email or password."));
    User user = candidate;
    if (candidate.status() == UserStatus.DELETION_PENDING) {
      if (accountDeletionApplicationService == null
          || !accountDeletionApplicationService.cancelForReturningUser(candidate.id())) {
        throw new UnauthorizedException("Invalid email or password.");
      }
      user =
          userRepository
              .findById(candidate.id())
              .orElseThrow(() -> new UnauthorizedException("Invalid email or password."));
    }
    if (!user.canAuthenticate()) {
      throw new UnauthorizedException("Invalid email or password.");
    }
    userRepository.touch(user.id(), clock.instant());
    return issueSession(user);
  }

  @Transactional
  public AuthResult refresh(String rawRefreshToken) {
    if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
      throw new UnauthorizedException("Refresh token is required.");
    }

    Instant now = clock.instant();
    RefreshSession current =
        refreshSessionRepository
            .findByTokenHash(TokenHashing.sha256(rawRefreshToken))
            .orElseThrow(() -> new UnauthorizedException("Refresh token is invalid."));
    User user =
        userRepository
            .findById(current.userId())
            .orElseThrow(() -> new UnauthorizedException("Refresh token is invalid."));

    if (!current.isActiveAt(now) || !user.canAuthenticate()) {
      refreshSessionRepository.revokeAllForUser(user.id(), now);
      throw new UnauthorizedException("Refresh token is expired or revoked.");
    }

    AuthResult result = issueSession(user);
    UUID replacementSessionId =
        refreshSessionRepository
            .findByTokenHash(TokenHashing.sha256(result.refreshToken()))
            .map(RefreshSession::id)
            .orElseThrow(() -> new IllegalStateException("new refresh session was not persisted"));
    refreshSessionRepository.revoke(current.id(), replacementSessionId, now);
    return result;
  }

  @Transactional
  public void logout(String rawRefreshToken) {
    if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
      return;
    }
    refreshSessionRepository
        .findByTokenHash(TokenHashing.sha256(rawRefreshToken))
        .ifPresent(session -> refreshSessionRepository.revoke(session.id(), null, clock.instant()));
  }

  private AuthResult issueSession(User user) {
    String rawRefreshToken = generateRefreshToken();
    Instant now = clock.instant();
    RefreshSession session =
        new RefreshSession(
            UUID.randomUUID(),
            user.id(),
            TokenHashing.sha256(rawRefreshToken),
            now.plus(authProperties.refreshTokenTtl()),
            null,
            null,
            now,
            null);
    refreshSessionRepository.save(session);
    JwtTokenService.IssuedAccessToken accessToken = jwtTokenService.issue(user.id(), user.email());
    return new AuthResult(
        accessToken.value(), rawRefreshToken, authProperties.accessTokenTtl().toSeconds());
  }

  private String generateRefreshToken() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String normalizeEmail(String email) {
    if (email == null) {
      return "";
    }
    return email.trim().toLowerCase(Locale.ROOT);
  }
}
