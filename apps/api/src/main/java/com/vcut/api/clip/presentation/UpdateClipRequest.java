package com.vcut.api.clip.presentation;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.math.BigDecimal;

public record UpdateClipRequest(
    BigDecimal startSeconds,
    BigDecimal endSeconds,
    String aspectRatio,
    BigDecimal cropX,
    BigDecimal cropY,
    BigDecimal cropZoom,
    String captionPreset,
    @JsonAlias("text") String captionText,
    String fontFamily,
    Integer fontSize,
    Integer fontWeight,
    String textColor,
    String backgroundColor,
    BigDecimal backgroundOpacity,
    String position,
    String animation,
    Integer expectedEditVersion) {}
