package com.vcut.api.video.application;

import com.vcut.api.shared.errors.ExternalProviderException;
import com.vcut.api.shared.errors.ValidationException;
import com.vcut.api.video.infrastructure.ExternalVideoImportProperties;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Enforces import timeouts, cooperative cancellation, bounded retries, and a durable DLQ record.
 */
@Component
public class ResilientExternalVideoImportRunner {

  private final ExternalVideoImportProperties properties;
  private final VideoImportDeadLetterRepository deadLetters;

  public ResilientExternalVideoImportRunner(
      ExternalVideoImportProperties properties, VideoImportDeadLetterRepository deadLetters) {
    this.properties = properties;
    this.deadLetters = deadLetters;
  }

  public ExternalVideoImporter.ImportedMedia run(
      ValidateExternalVideoImportUseCase.ValidatedImport validated,
      UUID importId,
      ExternalVideoImporter.CancellationSignal cancellation) {
    int attempts = Math.max(1, properties.maxAttempts());
    String failureCode = "IMPORT_FAILED";
    for (int attempt = 1; attempt <= attempts; attempt++) {
      if (cancellation.isCancelled()) {
        throw new CancellationException("External video import was cancelled.");
      }
      AtomicBoolean timedOut = new AtomicBoolean();
      FutureTask<ExternalVideoImporter.ImportedMedia> task =
          new FutureTask<>(
              () ->
                  validated
                      .importer()
                      .importMedia(
                          validated.provenance(),
                          validated.limits(),
                          importId,
                          () -> cancellation.isCancelled() || timedOut.get()));
      Thread thread = new Thread(task, "vcut-external-import-" + importId);
      thread.setDaemon(true);
      thread.start();
      try {
        ExternalVideoImporter.ImportedMedia media =
            task.get(validated.limits().timeout().toMillis(), TimeUnit.MILLISECONDS);
        if (cancellation.isCancelled()) {
          task.cancel(true);
          throw new CancellationException("External video import was cancelled.");
        }
        return media;
      } catch (TimeoutException exception) {
        failureCode = "IMPORT_TIMEOUT";
        timedOut.set(true);
        task.cancel(true);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        timedOut.set(true);
        task.cancel(true);
        throw new CancellationException("External video import was interrupted.");
      } catch (ExecutionException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof CancellationException cancellationException) {
          throw cancellationException;
        }
        if (cause instanceof ValidationException || cause instanceof IllegalArgumentException) {
          failureCode = "IMPORT_SOURCE_REJECTED";
          deadLetters.record(importId, validated.provenance().providerId(), failureCode, attempt);
          throw new ValidationException("The external video source could not be imported.");
        }
        failureCode = "IMPORT_PROVIDER_FAILED";
      }

      if (attempt < attempts) {
        waitBeforeRetry(properties.retryBackoff(), attempt, cancellation);
      }
    }
    deadLetters.record(importId, validated.provenance().providerId(), failureCode, attempts);
    throw new ExternalProviderException("The external video source could not be imported.");
  }

  private static void waitBeforeRetry(
      Duration baseDelay, int attempt, ExternalVideoImporter.CancellationSignal cancellation) {
    long multiplier = 1L << Math.min(attempt - 1, 10);
    long delayMillis = Math.multiplyExact(baseDelay.toMillis(), multiplier);
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
    while (!cancellation.isCancelled()) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        return;
      }
      try {
        TimeUnit.NANOSECONDS.sleep(remaining);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new CancellationException("External video import was interrupted.");
      }
    }
    throw new CancellationException("External video import was cancelled.");
  }
}
