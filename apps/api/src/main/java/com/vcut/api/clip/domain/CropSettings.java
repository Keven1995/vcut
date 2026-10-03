package com.vcut.api.clip.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record CropSettings(BigDecimal x, BigDecimal y, BigDecimal zoom) {

  public CropSettings {
    requireCoordinate(x, "x");
    requireCoordinate(y, "y");
    Objects.requireNonNull(zoom, "zoom");
    if (zoom.compareTo(BigDecimal.ONE) < 0 || zoom.compareTo(BigDecimal.valueOf(3)) > 0) {
      throw new IllegalArgumentException("zoom must be between 1 and 3");
    }
  }

  public static CropSettings centered() {
    return new CropSettings(BigDecimal.valueOf(0.5), BigDecimal.valueOf(0.5), BigDecimal.ONE);
  }

  private static void requireCoordinate(BigDecimal value, String field) {
    Objects.requireNonNull(value, field);
    if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException(field + " must be between 0 and 1");
    }
  }
}
