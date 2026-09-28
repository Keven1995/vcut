package com.vcut.api.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

class PasswordEncoderTest {

  @Test
  void storesPasswordAsArgon2HashAndMatchesOnlyTheOriginalValue() {
    Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    String encoded = encoder.encode("strong-password");

    assertThat(encoded).startsWith("$argon2");
    assertThat(encoded).doesNotContain("strong-password");
    assertThat(encoder.matches("strong-password", encoded)).isTrue();
    assertThat(encoder.matches("wrong-password", encoded)).isFalse();
  }
}
