package com.vcut.api.video.presentation;

import jakarta.validation.constraints.Pattern;

public record ConfirmVideoUploadRequest(
    @Pattern(regexp = "[0-9a-fA-F]{64}", message = "checksumSha256 must be a SHA-256 hex value")
        String checksumSha256) {}
