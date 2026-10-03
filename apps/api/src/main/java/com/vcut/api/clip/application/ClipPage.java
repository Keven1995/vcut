package com.vcut.api.clip.application;

import java.util.List;
import java.util.Objects;

public record ClipPage(List<ClipAggregate> content, int page, int size, long totalElements) {

  public ClipPage {
    content = List.copyOf(Objects.requireNonNull(content, "content"));
    if (page < 0 || size < 1 || totalElements < 0) {
      throw new IllegalArgumentException("invalid page");
    }
  }
}
