package com.vcut.api.project.application;

import com.vcut.api.project.domain.Project;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository {

  Project save(Project project);

  Optional<Project> findByIdForUser(UUID projectId, UUID userId);

  List<Project> findPage(UUID userId, int offset, int limit);

  long countByUserId(UUID userId);

  Project update(Project project);

  void delete(UUID projectId, UUID userId);
}
