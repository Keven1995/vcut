package com.vcut.api.project.application;

import com.vcut.api.project.domain.Project;
import com.vcut.api.project.domain.ProjectStatus;
import com.vcut.api.shared.errors.ConflictException;
import com.vcut.api.shared.errors.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectApplicationService {

  private final ProjectRepository projectRepository;
  private final Clock clock;

  @Autowired
  public ProjectApplicationService(ProjectRepository projectRepository) {
    this(projectRepository, Clock.systemUTC());
  }

  ProjectApplicationService(ProjectRepository projectRepository, Clock clock) {
    this.projectRepository = projectRepository;
    this.clock = clock;
  }

  @Transactional
  public Project create(UUID userId, String name) {
    Instant now = clock.instant();
    Project project =
        new Project(UUID.randomUUID(), userId, normalizeName(name), ProjectStatus.ACTIVE, now, now);
    try {
      return projectRepository.save(project);
    } catch (DuplicateKeyException exception) {
      throw new ConflictException("A project with this name already exists.");
    }
  }

  @Transactional(readOnly = true)
  public ProjectPage list(UUID userId, int page, int size) {
    long totalElements = projectRepository.countByUserId(userId);
    int offset = Math.multiplyExact(page, size);
    List<Project> projects = projectRepository.findPage(userId, offset, size);
    return new ProjectPage(projects, page, size, totalElements);
  }

  @Transactional(readOnly = true)
  public Project get(UUID userId, UUID projectId) {
    return findOwned(userId, projectId);
  }

  @Transactional
  public Project update(UUID userId, UUID projectId, String name, ProjectStatus status) {
    Project current = findOwned(userId, projectId);
    Project updated =
        new Project(
            current.id(),
            current.userId(),
            normalizeName(name),
            status,
            current.createdAt(),
            clock.instant());
    try {
      return projectRepository.update(updated);
    } catch (DuplicateKeyException exception) {
      throw new ConflictException("A project with this name already exists.");
    }
  }

  @Transactional
  public void delete(UUID userId, UUID projectId) {
    findOwned(userId, projectId);
    projectRepository.delete(projectId, userId);
  }

  private Project findOwned(UUID userId, UUID projectId) {
    return projectRepository
        .findByIdForUser(projectId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("Project not found."));
  }

  private static String normalizeName(String name) {
    return name == null ? "" : name.trim();
  }
}
