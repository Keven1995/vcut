package com.vcut.api.video.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vcut.api.video.application.ExternalVideoImporter;
import com.vcut.api.video.domain.ImportConsent;
import com.vcut.api.video.domain.VideoImportProvenance;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class FixtureLocalVideoImporterTest {

  private final FixtureLocalVideoImporter importer = new FixtureLocalVideoImporter();
  private final VideoImportProvenance provenance =
      new VideoImportProvenance(
          "fixture-local",
          URI.create("fixture://sample-video"),
          "sample-video",
          new ImportConsent("test-consent-v1", true, Instant.parse("2026-10-03T12:00:00Z")));
  private final ExternalVideoImporter.ImportLimits limits =
      new ExternalVideoImporter.ImportLimits(
          1_000_000, Duration.ofHours(1), Duration.ofSeconds(30));

  @Test
  void materializesTheKnownLocalFixtureWithoutCallingAnExternalSource() throws Exception {
    var media = importer.importMedia(provenance, limits, UUID.randomUUID(), () -> false);

    assertThat(media.filename()).isEqualTo("sample-video.mp4");
    assertThat(media.contentType()).isEqualTo("video/mp4");
    assertThat(media.sizeBytes()).isEqualTo(Files.size(media.localPath()));
    assertThat(Files.readString(media.localPath())).isEqualTo("VCUT-LOCAL-IMPORT-FIXTURE-v1\n");
    Files.deleteIfExists(media.localPath());
  }

  @Test
  void supportsOnlyTheSingleFixtureIdentifier() {
    assertThat(importer.supports(URI.create("fixture://sample-video"))).isTrue();
    assertThat(importer.supports(URI.create("fixture://another-video"))).isFalse();
    assertThat(importer.supports(URI.create("https://media.example/video.mp4"))).isFalse();
  }

  @Test
  void honorsCancellationBeforeCreatingATemporaryFile() {
    assertThatThrownBy(
            () -> importer.importMedia(provenance, limits, UUID.randomUUID(), () -> true))
        .isInstanceOf(CancellationException.class);
  }
}
