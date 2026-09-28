package com.vcut.api.project.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vcut.api.project.application.ProjectApplicationService;
import com.vcut.api.project.application.ProjectPage;
import com.vcut.api.project.domain.Project;
import com.vcut.api.project.domain.ProjectStatus;
import com.vcut.api.shared.correlation.CorrelationIdFilter;
import com.vcut.api.shared.errors.GlobalExceptionHandler;
import com.vcut.api.shared.errors.ResourceNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProjectControllerTest {

  private final ProjectApplicationService projectService = mock(ProjectApplicationService.class);
  private final UUID userId = UUID.randomUUID();
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ProjectController(projectService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();
  }

  @Test
  void createsProjectForAuthenticatedUser() throws Exception {
    Project project = project(userId, "Podcast");
    when(projectService.create(userId, "Podcast")).thenReturn(project);

    mockMvc
        .perform(
            post("/api/projects")
                .principal(authentication())
                .contentType("application/json")
                .content("{\"name\":\"Podcast\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(project.id().toString()))
        .andExpect(jsonPath("$.name").value("Podcast"));
  }

  @Test
  void returnsOnlyProjectsFromTheAuthenticatedAccount() throws Exception {
    when(projectService.list(userId, 0, 20)).thenReturn(new ProjectPage(List.of(), 0, 20, 0));

    mockMvc
        .perform(get("/api/projects").principal(authentication()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.content").isEmpty())
        .andExpect(jsonPath("$.page.totalElements").value(0));
  }

  @Test
  void mapsMissingOwnedProjectToNotFound() throws Exception {
    UUID projectId = UUID.randomUUID();
    when(projectService.get(userId, projectId))
        .thenThrow(new ResourceNotFoundException("Project not found."));

    mockMvc
        .perform(get("/api/projects/{projectId}", projectId).principal(authentication()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
  }

  private java.security.Principal authentication() {
    return () -> userId.toString();
  }

  private static Project project(UUID userId, String name) {
    Instant now = Instant.parse("2026-09-28T15:00:00Z");
    return new Project(UUID.randomUUID(), userId, name, ProjectStatus.ACTIVE, now, now);
  }
}
