package com.vcut.api.video.domain;

import java.time.Instant;
import java.util.Objects;

/** Explicit, auditable authorization supplied for one external media import. */
public record ImportConsent(String policyVersion, boolean rightsConfirmed, Instant acceptedAt) {

  public ImportConsent {
    if (policyVersion == null || policyVersion.isBlank() || policyVersion.length() > 64) {
      throw new IllegalArgumentException("policyVersion must contain 1-64 characters");
    }
    if (!rightsConfirmed) {
      throw new IllegalArgumentException("rights to import the source must be confirmed");
    }
    Objects.requireNonNull(acceptedAt, "acceptedAt");
  }
}
