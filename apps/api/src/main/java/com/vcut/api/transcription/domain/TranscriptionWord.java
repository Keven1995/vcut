package com.vcut.api.transcription.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record TranscriptionWord(
    String text, BigDecimal startSeconds, BigDecimal endSeconds, BigDecimal confidence) {

  public TranscriptionWord {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("word text must not be blank");
    }
    Objects.requireNonNull(startSeconds, "startSeconds");
    Objects.requireNonNull(endSeconds, "endSeconds");
    if (startSeconds.signum() < 0 || endSeconds.compareTo(startSeconds) <= 0) {
      throw new IllegalArgumentException("word timestamps are invalid");
    }
    requireConfidence(confidence);
  }

  private static void requireConfidence(BigDecimal value) {
    if (value != null && (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0)) {
      throw new IllegalArgumentException("confidence must be between 0 and 1");
    }
  }
}
