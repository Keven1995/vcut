package com.vcut.api.transcription.presentation;

import jakarta.validation.constraints.Pattern;

public record RequestTranscriptionRequest(
    @Pattern(regexp = "[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8})?") String language) {}
