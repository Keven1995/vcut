package com.vcut.api.project.application;

import com.vcut.api.project.domain.Project;
import java.util.List;

public record ProjectPage(List<Project> content, int page, int size, long totalElements) {

  public int totalPages() {
    return size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
  }
}
