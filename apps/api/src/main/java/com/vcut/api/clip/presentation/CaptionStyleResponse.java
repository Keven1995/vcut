package com.vcut.api.clip.presentation;

import com.vcut.api.clip.domain.CaptionStyle;
import java.math.BigDecimal;

public record CaptionStyleResponse(
    String fontFamily,
    int fontSize,
    int fontWeight,
    String textColor,
    String backgroundColor,
    BigDecimal backgroundOpacity,
    String position,
    String animation) {

  public static CaptionStyleResponse from(CaptionStyle style) {
    return new CaptionStyleResponse(
        style.fontFamily(),
        style.fontSize(),
        style.fontWeight(),
        style.textColor(),
        style.backgroundColor(),
        style.backgroundOpacity(),
        style.position().name(),
        style.animation().name());
  }
}
