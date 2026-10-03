package com.vcut.api.clip.presentation;

import com.vcut.api.clip.domain.CropSettings;
import java.math.BigDecimal;

public record CropSettingsResponse(BigDecimal x, BigDecimal y, BigDecimal zoom) {

  public static CropSettingsResponse from(CropSettings crop) {
    return new CropSettingsResponse(crop.x(), crop.y(), crop.zoom());
  }
}
