package com.vcut.api.clip.presentation;

import com.vcut.api.clip.domain.DurationPreference;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

public record RequestClipAnalysisRequest(
    DurationPreference durationPreference,
    @DecimalMin(value = "0.001") @DecimalMax(value = "90") BigDecimal customDurationSeconds) {}
