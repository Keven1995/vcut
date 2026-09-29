package com.vcut.api.video.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateVideoUploadRequest(
    @NotBlank @Size(max = 255) String filename,
    @NotBlank @Size(max = 127) String contentType,
    @Positive long sizeBytes) {}
