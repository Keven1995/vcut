package com.vcut.api.project.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(@NotBlank @Size(max = 120) String name) {}
