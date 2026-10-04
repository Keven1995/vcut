package com.vcut.api.subscription.infrastructure;

import java.net.URI;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vcut.payments.sandbox")
public record SandboxPaymentProperties(
    boolean enabled, String webhookSecret, long signatureToleranceSeconds, URI checkoutBaseUrl) {

  public SandboxPaymentProperties {
    webhookSecret = webhookSecret == null ? "" : webhookSecret;
    Objects.requireNonNull(checkoutBaseUrl, "checkoutBaseUrl");
    if (enabled && webhookSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
      throw new IllegalArgumentException(
          "enabled sandbox payments require a 32-byte webhook secret");
    }
    if (signatureToleranceSeconds < 1 || signatureToleranceSeconds > 3_600) {
      throw new IllegalArgumentException("signatureToleranceSeconds must be between 1 and 3600");
    }
    if (!checkoutBaseUrl.isAbsolute()
        || !("http".equals(checkoutBaseUrl.getScheme())
            || "https".equals(checkoutBaseUrl.getScheme()))) {
      throw new IllegalArgumentException("checkoutBaseUrl must be an absolute HTTP URL");
    }
  }
}
