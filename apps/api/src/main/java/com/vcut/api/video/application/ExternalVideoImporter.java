package com.vcut.api.video.application;

import com.vcut.api.video.domain.VideoImportProvenance;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/** Adapter for one explicitly supported external video source. */
public interface ExternalVideoImporter {

  String providerId();

  boolean supports(URI sourceUri);

  ImportedMedia importMedia(
      VideoImportProvenance provenance,
      ImportLimits limits,
      UUID importId,
      CancellationSignal cancellationSignal);

  record ImportLimits(long maxBytes, Duration maxDuration, Duration timeout) {
    public ImportLimits {
      if (maxBytes <= 0
          || maxDuration == null
          || maxDuration.isNegative()
          || maxDuration.isZero()
          || timeout == null
          || timeout.isNegative()
          || timeout.isZero()) {
        throw new IllegalArgumentException("external import limits must be positive");
      }
    }
  }

  record ImportedMedia(Path localPath, String filename, String contentType, long sizeBytes) {
    public ImportedMedia {
      if (localPath == null
          || filename == null
          || filename.isBlank()
          || contentType == null
          || contentType.isBlank()
          || sizeBytes <= 0) {
        throw new IllegalArgumentException("imported media metadata is invalid");
      }
    }
  }

  interface CancellationSignal {
    boolean isCancelled();
  }
}
