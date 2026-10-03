package com.vcut.api.clip.presentation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RenderRequest(@NotNull @Positive Integer editVersion) {}
