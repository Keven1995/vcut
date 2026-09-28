package com.vcut.api.auth.infrastructure;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenService {

  private final AuthProperties properties;
  private final Clock clock;

  @Autowired
  public JwtTokenService(AuthProperties properties) {
    this(properties, Clock.systemUTC());
  }

  JwtTokenService(AuthProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  public IssuedAccessToken issue(UUID userId, String email) {
    Instant issuedAt = clock.instant();
    Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
    String token =
        Jwts.builder()
            .subject(userId.toString())
            .claim("email", email)
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(expiresAt))
            .id(UUID.randomUUID().toString())
            .signWith(signingKey(), Jwts.SIG.HS256)
            .compact();
    return new IssuedAccessToken(token, expiresAt);
  }

  public AuthenticatedToken parse(String token) {
    Claims claims =
        Jwts.parser()
            .verifyWith(signingKey())
            .clock(() -> Date.from(clock.instant()))
            .build()
            .parseSignedClaims(token)
            .getPayload();
    return new AuthenticatedToken(
        UUID.fromString(claims.getSubject()), claims.get("email", String.class));
  }

  private SecretKey signingKey() {
    byte[] secret =
        properties.jwtSecret() == null
            ? new byte[0]
            : properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
    if (secret.length < 32) {
      throw new IllegalStateException("JWT_SECRET must contain at least 32 characters");
    }
    return Keys.hmacShaKeyFor(secret);
  }

  public record IssuedAccessToken(String value, Instant expiresAt) {}

  public record AuthenticatedToken(UUID userId, String email) {}
}
