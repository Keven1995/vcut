package com.vcut.api.project.presentation;

import com.vcut.api.project.application.ProjectPage;
import java.util.List;

public record ProjectPageResponse(List<ProjectResponse> content, PageMetadata page) {

  public static ProjectPageResponse from(ProjectPage projectPage) {
    return new ProjectPageResponse(
        projectPage.content().stream().map(ProjectResponse::from).toList(),
        new PageMetadata(
            projectPage.page(),
            projectPage.size(),
            projectPage.totalElements(),
            projectPage.totalPages()));
  }

  public record PageMetadata(int page, int size, long totalElements, int totalPages) {}
}
