package com.vcut.api.clip.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record PublicationMetadataRequest(
    @NotBlank @Size(max = 100) String title,
    @NotNull @Size(max = 2200) String description,
    @NotNull @Size(max = 20)
        List<@NotBlank @Pattern(regexp = "^#[\\p{L}\\p{N}_]{1,63}$") String> hashtags) {}
