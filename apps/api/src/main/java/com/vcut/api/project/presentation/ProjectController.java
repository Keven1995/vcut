package com.vcut.api.project.presentation;

import com.vcut.api.project.application.ProjectApplicationService;
import com.vcut.api.project.domain.Project;
import com.vcut.api.shared.errors.UnauthorizedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/projects")
public class ProjectController {

  private final ProjectApplicationService projectApplicationService;

  public ProjectController(ProjectApplicationService projectApplicationService) {
    this.projectApplicationService = projectApplicationService;
  }

  @PostMapping
  public ResponseEntity<ProjectResponse> create(
      Principal authentication, @Valid @RequestBody CreateProjectRequest request) {
    Project project = projectApplicationService.create(userId(authentication), request.name());
    return ResponseEntity.status(HttpStatus.CREATED).body(ProjectResponse.from(project));
  }

  @GetMapping
  public ProjectPageResponse list(
      Principal authentication,
      @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
      @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
    return ProjectPageResponse.from(
        projectApplicationService.list(userId(authentication), page, size));
  }

  @GetMapping("/{projectId}")
  public ProjectResponse get(Principal authentication, @PathVariable("projectId") UUID projectId) {
    return ProjectResponse.from(projectApplicationService.get(userId(authentication), projectId));
  }

  @PutMapping("/{projectId}")
  public ProjectResponse update(
      Principal authentication,
      @PathVariable("projectId") UUID projectId,
      @Valid @RequestBody UpdateProjectRequest request) {
    return ProjectResponse.from(
        projectApplicationService.update(
            userId(authentication), projectId, request.name(), request.status()));
  }

  @DeleteMapping("/{projectId}")
  public ResponseEntity<Void> delete(
      Principal authentication, @PathVariable("projectId") UUID projectId) {
    projectApplicationService.delete(userId(authentication), projectId);
    return ResponseEntity.noContent().build();
  }

  private static UUID userId(Principal authentication) {
    if (authentication == null || authentication.getName() == null) {
      throw new UnauthorizedException("Authentication is required.");
    }
    try {
      return UUID.fromString(authentication.getName());
    } catch (IllegalArgumentException exception) {
      throw new UnauthorizedException("Authentication is invalid.");
    }
  }
}
