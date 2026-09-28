package com.vcut.api.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretLogSanitizerTest {

  @Test
  void masksCredentialsTokensAndSignedUrlParameters() {
    String input =
        "password=pass token=abc authorization=Bearer-secret "
            + "https://storage.local/file?X-Amz-Signature=signed-value";

    String sanitized = SecretLogSanitizer.sanitize(input);

    assertThat(sanitized).doesNotContain("=pass", "=abc", "Bearer-secret", "=signed-value");
    assertThat(sanitized).contains("password=[REDACTED]", "X-Amz-Signature=[REDACTED]");
  }
}
