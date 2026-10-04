package com.vcut.api.video.infrastructure;

import com.vcut.api.video.application.ExternalVideoImporter;
import com.vcut.api.video.domain.VideoImportProvenance;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Local-only fixture source used to exercise the import flow without external network access. */
@Component
@ConditionalOnProperty(
    prefix = "vcut.external-import",
    name = "fixture-adapter-enabled",
    havingValue = "true")
public class FixtureLocalVideoImporter implements ExternalVideoImporter {

  private static final String FIXTURE_URI = "fixture://sample-video";
  private static final byte[] FIXTURE_CONTENT =
      "VCUT-LOCAL-IMPORT-FIXTURE-v1\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

  @Override
  public String providerId() {
    return "fixture-local";
  }

  @Override
  public boolean supports(java.net.URI sourceUri) {
    return FIXTURE_URI.equals(sourceUri.toString());
  }

  @Override
  public ImportedMedia importMedia(
      VideoImportProvenance provenance,
      ImportLimits limits,
      UUID importId,
      CancellationSignal cancellationSignal) {
    if (!providerId().equals(provenance.providerId()) || !supports(provenance.sourceUri())) {
      throw new IllegalArgumentException("Fixture importer received an unsupported source.");
    }
    if (cancellationSignal.isCancelled()) {
      throw new CancellationException("Fixture import was cancelled.");
    }
    if (FIXTURE_CONTENT.length > limits.maxBytes()) {
      throw new IllegalArgumentException("Fixture exceeds the configured import size limit.");
    }
    try {
      Path file = Files.createTempFile("vcut-fixture-import-" + importId + "-", ".mp4");
      Files.write(file, FIXTURE_CONTENT);
      if (cancellationSignal.isCancelled()) {
        Files.deleteIfExists(file);
        throw new CancellationException("Fixture import was cancelled.");
      }
      return new ImportedMedia(file, "sample-video.mp4", "video/mp4", FIXTURE_CONTENT.length);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to prepare the local import fixture.", exception);
    }
  }
}
