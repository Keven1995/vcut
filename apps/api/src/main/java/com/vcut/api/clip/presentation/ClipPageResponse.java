package com.vcut.api.clip.presentation;

import com.vcut.api.clip.application.ClipPage;
import java.util.List;

public record ClipPageResponse(
    List<ClipResponse> content, int page, int size, long totalElements, int totalPages) {

  public static ClipPageResponse from(ClipPage result) {
    int totalPages = (int) Math.ceil((double) result.totalElements() / result.size());
    return new ClipPageResponse(
        result.content().stream().map(ClipResponse::from).toList(),
        result.page(),
        result.size(),
        result.totalElements(),
        totalPages);
  }
}
