package com.vcut.api.video.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ImportVideoRequest(
    @NotNull UUID importId,
    @NotBlank @Size(max = 64) String providerId,
    @NotBlank @Size(max = 2048) String sourceUrl,
    @Size(max = 255) String externalAssetId,
    @NotBlank @Size(max = 64) String consentPolicyVersion,
    boolean rightsConfirmed) {}
