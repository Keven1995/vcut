package com.vcut.api.project.presentation;

import com.vcut.api.project.domain.ProjectStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateProjectRequest(
    @NotBlank @Size(max = 120) String name, @NotNull ProjectStatus status) {}
