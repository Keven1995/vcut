package com.vcut.api.clip.domain;

public enum CaptionAnimation {
  NONE,
  FADE,
  POP,
  KARAOKE;

  public static CaptionAnimation fromValue(String value) {
    if (value == null) {
      throw new IllegalArgumentException("caption animation is required");
    }
    try {
      return valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Unsupported caption animation: " + value, exception);
    }
  }
}
