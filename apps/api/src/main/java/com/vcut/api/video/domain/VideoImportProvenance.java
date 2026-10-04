package com.vcut.api.video.domain;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/** Records where an imported asset came from and the consent governing its import. */
public record VideoImportProvenance(
    String providerId, URI sourceUri, String externalAssetId, ImportConsent consent) {

  public VideoImportProvenance {
    if (providerId == null || providerId.isBlank() || providerId.length() > 64) {
      throw new IllegalArgumentException("providerId must contain 1-64 characters");
    }
    Objects.requireNonNull(sourceUri, "sourceUri");
    if (externalAssetId != null && externalAssetId.length() > 255) {
      throw new IllegalArgumentException("externalAssetId is too long");
    }
    Objects.requireNonNull(consent, "consent");
  }

  public Instant consentAcceptedAt() {
    return consent.acceptedAt();
  }

  /** Return only the source origin; paths, query strings, and fragments may contain secrets. */
  public String sourceOrigin() {
    int port = sourceUri.getPort();
    String authority = sourceUri.getHost() + (port < 0 ? "" : ":" + port);
    return sourceUri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://" + authority;
  }
}
