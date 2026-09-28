package com.vcut.api.project.presentation;

import com.vcut.api.project.domain.Project;
import com.vcut.api.project.domain.ProjectStatus;
import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
    UUID id, String name, ProjectStatus status, Instant createdAt, Instant updatedAt) {

  public static ProjectResponse from(Project project) {
    return new ProjectResponse(
        project.id(), project.name(), project.status(), project.createdAt(), project.updatedAt());
  }
}
