package com.vcut.api.shared.observability;

import java.util.regex.Pattern;

public final class SecretLogSanitizer {

  private static final Pattern SECRET_PARAMETER =
      Pattern.compile(
          "(?i)(authorization|password|token|secret|api[-_]key|signature)(\\s*[:=]\\s*)([^\\s,&]+)");

  private SecretLogSanitizer() {}

  public static String sanitize(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    return SECRET_PARAMETER.matcher(value).replaceAll("$1$2[REDACTED]");
  }
}
