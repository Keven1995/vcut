package com.vcut.api.video.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vcut.api.shared.errors.ExternalProviderException;
import com.vcut.api.video.domain.ImportConsent;
import com.vcut.api.video.domain.VideoImportProvenance;
import com.vcut.api.video.infrastructure.ExternalVideoImportProperties;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResilientExternalVideoImportRunnerTest {

  private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID IMPORT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final VideoImportProvenance PROVENANCE =
      new VideoImportProvenance(
          "fixture-local",
          URI.create("fixture://sample-video"),
          "sample-video",
          new ImportConsent("test-consent-v1", true, Instant.parse("2026-10-03T12:00:00Z")));

  @Test
  void retriesATimedOutAttemptAndCompletesWithTheNextAttempt(@TempDir Path directory)
      throws Exception {
    AtomicInteger calls = new AtomicInteger();
    var importer =
        new TestImporter() {
          @Override
          public ImportedMedia importMedia(
              VideoImportProvenance provenance,
              ImportLimits limits,
              UUID importId,
              CancellationSignal cancellationSignal) {
            if (calls.incrementAndGet() == 1) {
              try {
                Thread.sleep(250);
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", exception);
              }
            }
            Path output = directory.resolve("fixture.mp4");
            try {
              Files.writeString(output, "fixture");
            } catch (java.io.IOException exception) {
              throw new IllegalStateException(exception);
            }
            return new ImportedMedia(output, "fixture.mp4", "video/mp4", 7);
          }
        };
    var deadLetters = new RecordingDeadLetters();
    var runner = runner(deadLetters, 2, Duration.ZERO);
    var validated = validImport(importer, Duration.ofMillis(30));

    var media = runner.run(validated, IMPORT_ID, () -> false);

    assertThat(media.filename()).isEqualTo("fixture.mp4");
    assertThat(calls.get()).isEqualTo(2);
    assertThat(deadLetters.entries).isEmpty();
  }

  @Test
  void recordsSanitizedDeadLetterAfterTheRetryLimit() {
    var importer = new TestImporter();
    var deadLetters = new RecordingDeadLetters();
    var runner = runner(deadLetters, 2, Duration.ZERO);

    assertThatThrownBy(
            () -> runner.run(validImport(importer, Duration.ofSeconds(1)), IMPORT_ID, () -> false))
        .isInstanceOf(ExternalProviderException.class);

    assertThat(importer.calls.get()).isEqualTo(2);
    assertThat(deadLetters.entries)
        .containsExactly(new DeadLetter(IMPORT_ID, "fixture-local", "IMPORT_PROVIDER_FAILED", 2));
  }

  @Test
  void cancellationStopsBeforeCallingTheProvider() {
    var importer = new TestImporter();
    var runner = runner(new RecordingDeadLetters(), 3, Duration.ZERO);
    AtomicBoolean cancelled = new AtomicBoolean(true);

    assertThatThrownBy(
            () ->
                runner.run(validImport(importer, Duration.ofSeconds(1)), IMPORT_ID, cancelled::get))
        .isInstanceOf(java.util.concurrent.CancellationException.class);

    assertThat(importer.calls.get()).isZero();
  }

  private static ResilientExternalVideoImportRunner runner(
      RecordingDeadLetters deadLetters, int attempts, Duration backoff) {
    return new ResilientExternalVideoImportRunner(
        new ExternalVideoImportProperties(
            false, true, 100_000, Duration.ofHours(1), Duration.ofMinutes(1), attempts, backoff),
        deadLetters);
  }

  private static ValidateExternalVideoImportUseCase.ValidatedImport validImport(
      ExternalVideoImporter importer, Duration timeout) {
    return new ValidateExternalVideoImportUseCase.ValidatedImport(
        USER_ID,
        PROJECT_ID,
        importer,
        PROVENANCE,
        new ExternalVideoImporter.ImportLimits(100_000, Duration.ofHours(1), timeout));
  }

  private static class TestImporter implements ExternalVideoImporter {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public String providerId() {
      return "fixture-local";
    }

    @Override
    public boolean supports(URI sourceUri) {
      return sourceUri.equals(PROVENANCE.sourceUri());
    }

    @Override
    public ImportedMedia importMedia(
        VideoImportProvenance provenance,
        ImportLimits limits,
        UUID importId,
        CancellationSignal cancellationSignal) {
      calls.incrementAndGet();
      throw new IllegalStateException("provider failure with private source URL");
    }
  }

  private static final class RecordingDeadLetters implements VideoImportDeadLetterRepository {
    private final java.util.ArrayList<DeadLetter> entries = new java.util.ArrayList<>();

    @Override
    public void record(UUID importId, String providerId, String failureCode, int attempts) {
      entries.add(new DeadLetter(importId, providerId, failureCode, attempts));
    }
  }

  private record DeadLetter(UUID importId, String providerId, String failureCode, int attempts) {}
}
