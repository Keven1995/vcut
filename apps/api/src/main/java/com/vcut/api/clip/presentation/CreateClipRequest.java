package com.vcut.api.clip.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateClipRequest(
    @NotNull UUID candidateId, @NotBlank String aspectRatio, @NotBlank String captionPreset) {}
