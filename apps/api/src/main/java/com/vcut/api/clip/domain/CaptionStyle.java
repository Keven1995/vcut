package com.vcut.api.clip.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

public record CaptionStyle(
    String fontFamily,
    int fontSize,
    int fontWeight,
    String textColor,
    String backgroundColor,
    BigDecimal backgroundOpacity,
    CaptionPosition position,
    CaptionAnimation animation) {

  private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

  public CaptionStyle {
    requireText(fontFamily, "fontFamily", 128);
    if (fontSize < 8 || fontSize > 144) {
      throw new IllegalArgumentException("fontSize must be between 8 and 144");
    }
    if (fontWeight < 100 || fontWeight > 900 || fontWeight % 100 != 0) {
      throw new IllegalArgumentException(
          "fontWeight must be a multiple of 100 between 100 and 900");
    }
    requireColor(textColor, "textColor");
    requireColor(backgroundColor, "backgroundColor");
    Objects.requireNonNull(backgroundOpacity, "backgroundOpacity");
    if (backgroundOpacity.signum() < 0 || backgroundOpacity.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("backgroundOpacity must be between 0 and 1");
    }
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(animation, "animation");
  }

  private static void requireColor(String value, String field) {
    if (value == null || !HEX_COLOR.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a six-digit hexadecimal color");
    }
  }

  private static void requireText(String value, String field, int maxLength) {
    if (value == null || value.isBlank() || value.length() > maxLength) {
      throw new IllegalArgumentException(
          field + " must be non-blank and at most " + maxLength + " characters");
    }
    if (value.indexOf('\n') >= 0
        || value.indexOf('\r') >= 0
        || value.indexOf('/') >= 0
        || value.indexOf('\\') >= 0) {
      throw new IllegalArgumentException(field + " contains unsupported characters");
    }
  }
}
