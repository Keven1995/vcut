package com.vcut.api.clip.domain;

public enum CaptionPosition {
  TOP,
  CENTER,
  BOTTOM;

  public static CaptionPosition fromValue(String value) {
    if (value == null) {
      throw new IllegalArgumentException("caption position is required");
    }
    try {
      return valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Unsupported caption position: " + value, exception);
    }
  }
}
