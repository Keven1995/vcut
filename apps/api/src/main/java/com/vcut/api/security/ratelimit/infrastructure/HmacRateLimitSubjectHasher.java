package com.vcut.api.security.ratelimit.infrastructure;

import com.vcut.api.auth.infrastructure.AuthProperties;
import com.vcut.api.security.ratelimit.application.RateLimitSubjectHasher;
import com.vcut.api.security.ratelimit.domain.RateLimitSubjectScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class HmacRateLimitSubjectHasher implements RateLimitSubjectHasher {

  private static final String DOMAIN_SEPARATOR = "vcut-rate-limit-subject-v1:";
  private final String jwtSecret;

  public HmacRateLimitSubjectHasher(AuthProperties authProperties) {
    this.jwtSecret = authProperties.jwtSecret();
  }

  @Override
  public String hash(RateLimitSubjectScope scope, String subject) {
    if (jwtSecret == null || jwtSecret.isBlank()) {
      throw new IllegalStateException("rate limit subject hashing key is not configured");
    }
    try {
      byte[] derivedKey =
          MessageDigest.getInstance("SHA-256")
              .digest((DOMAIN_SEPARATOR + jwtSecret).getBytes(StandardCharsets.UTF_8));
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(derivedKey, "HmacSHA256"));
      byte[] digest = mac.doFinal((scope.name() + ":" + subject).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (java.security.GeneralSecurityException exception) {
      throw new IllegalStateException("could not hash rate limit subject", exception);
    }
  }
}
