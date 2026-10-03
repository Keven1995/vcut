package com.vcut.api.clip.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record CaptionCue(UUID id, String text, BigDecimal startSeconds, BigDecimal endSeconds) {

  public CaptionCue {
    Objects.requireNonNull(id, "id");
    if (text == null || text.isBlank() || text.length() > 1000) {
      throw new IllegalArgumentException(
          "caption text must be non-blank and at most 1000 characters");
    }
    Objects.requireNonNull(startSeconds, "startSeconds");
    Objects.requireNonNull(endSeconds, "endSeconds");
    if (startSeconds.signum() < 0 || endSeconds.compareTo(startSeconds) <= 0) {
      throw new IllegalArgumentException("caption cue interval is invalid");
    }
  }
}
