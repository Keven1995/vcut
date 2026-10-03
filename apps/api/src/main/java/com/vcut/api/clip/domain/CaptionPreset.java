package com.vcut.api.clip.domain;

public enum CaptionPreset {
  MINIMAL,
  BOLD,
  KARAOKE,
  PODCAST,
  GAMING,
  TIKTOK;

  public static CaptionPreset fromValue(String value) {
    if (value == null) {
      throw new IllegalArgumentException("captionPreset is required");
    }
    try {
      return valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Unsupported caption preset: " + value, exception);
    }
  }

  public CaptionStyle defaultStyle() {
    return switch (this) {
      case MINIMAL ->
          new CaptionStyle(
              "Inter",
              32,
              400,
              "#FFFFFF",
              "#000000",
              java.math.BigDecimal.valueOf(0.35),
              CaptionPosition.BOTTOM,
              CaptionAnimation.NONE);
      case BOLD ->
          new CaptionStyle(
              "Arial",
              48,
              800,
              "#FFFFFF",
              "#000000",
              java.math.BigDecimal.valueOf(0.65),
              CaptionPosition.CENTER,
              CaptionAnimation.POP);
      case KARAOKE ->
          new CaptionStyle(
              "Arial",
              44,
              700,
              "#FFE600",
              "#000000",
              java.math.BigDecimal.valueOf(0.55),
              CaptionPosition.BOTTOM,
              CaptionAnimation.KARAOKE);
      case PODCAST ->
          new CaptionStyle(
              "Inter",
              36,
              600,
              "#FFFFFF",
              "#202020",
              java.math.BigDecimal.valueOf(0.75),
              CaptionPosition.BOTTOM,
              CaptionAnimation.FADE);
      case GAMING ->
          new CaptionStyle(
              "Arial",
              40,
              800,
              "#00FF66",
              "#101010",
              java.math.BigDecimal.valueOf(0.70),
              CaptionPosition.TOP,
              CaptionAnimation.POP);
      case TIKTOK ->
          new CaptionStyle(
              "Arial",
              42,
              700,
              "#FFFFFF",
              "#FE2C55",
              java.math.BigDecimal.valueOf(0.60),
              CaptionPosition.CENTER,
              CaptionAnimation.POP);
    };
  }
}
