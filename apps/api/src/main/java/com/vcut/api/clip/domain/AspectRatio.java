package com.vcut.api.clip.domain;

public enum AspectRatio {
  PORTRAIT("9:16", 1080, 1920),
  LANDSCAPE("16:9", 1920, 1080);

  public static final AspectRatio NINE_SIXTEEN = PORTRAIT;
  public static final AspectRatio SIXTEEN_NINE = LANDSCAPE;

  private final String value;
  private final int width;
  private final int height;

  AspectRatio(String value, int width, int height) {
    this.value = value;
    this.width = width;
    this.height = height;
  }

  public String value() {
    return value;
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  public static AspectRatio fromValue(String value) {
    if (value == null) {
      throw new IllegalArgumentException("aspectRatio is required");
    }
    return switch (value.trim().toUpperCase()) {
      case "9:16", "PORTRAIT", "NINE_SIXTEEN" -> PORTRAIT;
      case "16:9", "LANDSCAPE", "SIXTEEN_NINE" -> LANDSCAPE;
      default -> throw new IllegalArgumentException("Unsupported aspect ratio: " + value);
    };
  }
}
