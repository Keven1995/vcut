package com.vcut.api.clip.application;

import java.math.BigDecimal;

public record ClipEditCommand(
    BigDecimal startSeconds,
    BigDecimal endSeconds,
    String aspectRatio,
    BigDecimal cropX,
    BigDecimal cropY,
    BigDecimal cropZoom,
    String captionPreset,
    String captionText,
    String fontFamily,
    Integer fontSize,
    Integer fontWeight,
    String textColor,
    String backgroundColor,
    BigDecimal backgroundOpacity,
    String position,
    String animation) {

  public boolean hasStyleOverride() {
    return fontFamily != null
        || fontSize != null
        || fontWeight != null
        || textColor != null
        || backgroundColor != null
        || backgroundOpacity != null
        || position != null
        || animation != null;
  }
}
