package com.vcut.api.project.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vcut.api.project.domain.Project;
import com.vcut.api.project.domain.ProjectStatus;
import com.vcut.api.shared.errors.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectApplicationServiceTest {

  @Mock private ProjectRepository projectRepository;

  @Test
  void scopesLookupByAuthenticatedUserAndHidesCrossAccountProjects() {
    UUID projectId = UUID.randomUUID();
    UUID authenticatedUserId = UUID.randomUUID();
    ProjectApplicationService service =
        new ProjectApplicationService(
            projectRepository, Clock.fixed(Instant.parse("2026-09-28T15:00:00Z"), ZoneOffset.UTC));
    when(projectRepository.findByIdForUser(projectId, authenticatedUserId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(authenticatedUserId, projectId))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessage("Project not found.");

    verify(projectRepository).findByIdForUser(projectId, authenticatedUserId);
  }

  @Test
  void createsActiveProjectForAuthenticatedUser() {
    UUID userId = UUID.randomUUID();
    ProjectApplicationService service =
        new ProjectApplicationService(
            projectRepository, Clock.fixed(Instant.parse("2026-09-28T15:00:00Z"), ZoneOffset.UTC));
    when(projectRepository.save(org.mockito.ArgumentMatchers.any(Project.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.create(userId, "  Podcast de setembro  ");

    verify(projectRepository)
        .save(
            org.mockito.ArgumentMatchers.argThat(
                project ->
                    project.userId().equals(userId)
                        && project.name().equals("Podcast de setembro")
                        && project.status() == ProjectStatus.ACTIVE));
  }
}
