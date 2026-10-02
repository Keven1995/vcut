package com.vcut.api.transcription.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record TranscriptionSegment(
    String text,
    BigDecimal startSeconds,
    BigDecimal endSeconds,
    BigDecimal confidence,
    List<TranscriptionWord> words) {

  public TranscriptionSegment {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("segment text must not be blank");
    }
    Objects.requireNonNull(startSeconds, "startSeconds");
    Objects.requireNonNull(endSeconds, "endSeconds");
    if (startSeconds.signum() < 0 || endSeconds.compareTo(startSeconds) <= 0) {
      throw new IllegalArgumentException("segment timestamps are invalid");
    }
    if (confidence != null
        && (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0)) {
      throw new IllegalArgumentException("confidence must be between 0 and 1");
    }
    words = List.copyOf(Objects.requireNonNull(words, "words"));
    BigDecimal previousEnd = startSeconds;
    for (TranscriptionWord word : words) {
      if (word.startSeconds().compareTo(startSeconds) < 0
          || word.endSeconds().compareTo(endSeconds) > 0
          || word.startSeconds().compareTo(previousEnd) < 0) {
        throw new IllegalArgumentException("word timestamps must be ordered inside segment");
      }
      previousEnd = word.endSeconds();
    }
  }
}
