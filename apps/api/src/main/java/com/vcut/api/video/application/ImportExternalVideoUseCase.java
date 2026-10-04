package com.vcut.api.video.application;

import com.vcut.api.shared.errors.ConflictException;
import com.vcut.api.shared.errors.ValidationException;
import com.vcut.api.usage.application.RetentionApplicationService;
import com.vcut.api.usage.domain.RetentionAssetType;
import com.vcut.api.video.domain.Video;
import com.vcut.api.video.domain.VideoImportProvenance;
import com.vcut.api.video.infrastructure.UploadProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Imports an authorized source into owner-scoped storage and creates a standard uploaded video. */
@Service
@ConditionalOnBean(ExternalVideoImportStorage.class)
public class ImportExternalVideoUseCase {

  private final ValidateExternalVideoImportUseCase validator;
  private final VideoRepository videoRepository;
  private final VideoImportProvenanceRepository provenanceRepository;
  private final ExternalVideoImportStorage storage;
  private final UploadProperties uploadProperties;
  private final VideoImportCancellationRegistry cancellationRegistry;
  private final ResilientExternalVideoImportRunner importRunner;
  private final com.vcut.api.usage.application.UsageApplicationService usageApplicationService;
  private final RetentionApplicationService retentionApplicationService;
  private final Clock clock;

  @Autowired
  public ImportExternalVideoUseCase(
      ValidateExternalVideoImportUseCase validator,
      VideoRepository videoRepository,
      VideoImportProvenanceRepository provenanceRepository,
      ExternalVideoImportStorage storage,
      UploadProperties uploadProperties,
      VideoImportCancellationRegistry cancellationRegistry,
      ResilientExternalVideoImportRunner importRunner,
      com.vcut.api.usage.application.UsageApplicationService usageApplicationService,
      RetentionApplicationService retentionApplicationService) {
    this(
        validator,
        videoRepository,
        provenanceRepository,
        storage,
        uploadProperties,
        cancellationRegistry,
        importRunner,
        usageApplicationService,
        retentionApplicationService,
        Clock.systemUTC());
  }

  ImportExternalVideoUseCase(
      ValidateExternalVideoImportUseCase validator,
      VideoRepository videoRepository,
      VideoImportProvenanceRepository provenanceRepository,
      ExternalVideoImportStorage storage,
      UploadProperties uploadProperties,
      VideoImportCancellationRegistry cancellationRegistry,
      ResilientExternalVideoImportRunner importRunner,
      com.vcut.api.usage.application.UsageApplicationService usageApplicationService,
      RetentionApplicationService retentionApplicationService,
      Clock clock) {
    this.validator = validator;
    this.videoRepository = videoRepository;
    this.provenanceRepository = provenanceRepository;
    this.storage = storage;
    this.uploadProperties = uploadProperties;
    this.cancellationRegistry = cancellationRegistry;
    this.importRunner = importRunner;
    this.usageApplicationService = usageApplicationService;
    this.retentionApplicationService = retentionApplicationService;
    this.clock = clock;
  }

  @Transactional
  public ImportedVideo execute(
      UUID userId, UUID projectId, ValidateExternalVideoImportUseCase.ImportRequest request) {
    if (request == null || request.importId() == null) {
      throw new ValidationException("importId is required.");
    }
    ExternalVideoImporter.ImportedMedia media = null;
    String objectKey = null;
    boolean storageWriteStarted = false;
    try (var registration = cancellationRegistry.register(request.importId(), userId)) {
      ValidateExternalVideoImportUseCase.ValidatedImport validated =
          validator.validate(userId, projectId, request);
      var planLimits = usageApplicationService.limitsForUser(userId);
      ExternalVideoImporter.ImportLimits limits =
          new ExternalVideoImporter.ImportLimits(
              Math.min(
                  Math.min(validated.limits().maxBytes(), uploadProperties.maxFileSizeBytes()),
                  planLimits.maxFileSizeBytes()),
              Duration.ofSeconds(
                  Math.min(
                      Math.min(
                          validated.limits().maxDuration().toSeconds(),
                          uploadProperties.maxDurationSeconds()),
                      planLimits.maxDurationSeconds())),
              validated.limits().timeout());
      media =
          importRunner.run(
              new ValidateExternalVideoImportUseCase.ValidatedImport(
                  validated.userId(),
                  validated.projectId(),
                  validated.importer(),
                  validated.provenance(),
                  limits),
              request.importId(),
              registration::isCancelled);
      validateMedia(media, limits.maxBytes());
      usageApplicationService.assertUploadAllowed(userId, media.sizeBytes());
      if (registration.isCancelled()) {
        throw new CancellationException("External video import was cancelled.");
      }

      UUID videoId = UUID.randomUUID();
      String extension = extension(media.filename());
      String contentType = media.contentType().toLowerCase(Locale.ROOT);
      objectKey =
          "users/%s/projects/%s/source/%s/original.%s"
              .formatted(userId, projectId, videoId, extension);
      storage.ensureBucket();
      storageWriteStarted = true;
      var metadata = storage.upload(media.localPath(), objectKey, contentType, media.sizeBytes());
      if (registration.isCancelled()) {
        throw new CancellationException("External video import was cancelled.");
      }

      if (!metadata.objectKey().equals(objectKey)
          || metadata.contentLength() != media.sizeBytes()
          || metadata.contentType() == null
          || !contentType.equalsIgnoreCase(metadata.contentType())) {
        throw new ValidationException("Imported object metadata did not match the source media.");
      }
      Instant now = clock.instant();
      Video video =
          Video.uploading(
                  videoId,
                  userId,
                  projectId,
                  objectKey,
                  media.filename(),
                  contentType,
                  media.sizeBytes(),
                  now)
              .uploaded(metadata.contentLength(), metadata.checksumSha256(), now);
      videoRepository.save(video);
      provenanceRepository.save(videoId, validated.provenance());
      retentionApplicationService.register(
          userId, projectId, objectKey, RetentionAssetType.ORIGINAL, metadata.contentLength());
      return new ImportedVideo(video, validated.provenance());
    } catch (RuntimeException exception) {
      if (storageWriteStarted && objectKey != null) {
        try {
          storage.delete(objectKey);
        } catch (RuntimeException ignored) {
          // The object key is isolated to this attempted import and safe for a retry cleanup.
        }
      }
      if (exception instanceof CancellationException) {
        throw new ConflictException("External video import was cancelled.");
      }
      throw exception;
    } finally {
      if (media != null) {
        try {
          Files.deleteIfExists(media.localPath());
        } catch (IOException ignored) {
          // Temporary source files are best-effort cleaned; no path or source URL is logged.
        }
      }
    }
  }

  private void validateMedia(ExternalVideoImporter.ImportedMedia media, long maxBytes) {
    Path localPath = media.localPath();
    if (!Files.isRegularFile(localPath)) {
      throw new ValidationException("Imported media file is unavailable.");
    }
    try {
      long actualSize = Files.size(localPath);
      if (actualSize <= 0 || actualSize != media.sizeBytes() || actualSize > maxBytes) {
        throw new ValidationException("Imported media does not meet the configured size limit.");
      }
    } catch (IOException exception) {
      throw new ValidationException("Imported media file is unavailable.");
    }
    String filename = media.filename();
    if (filename.contains("/")
        || filename.contains("\\")
        || filename.length() > 255
        || filename.chars().anyMatch(Character::isISOControl)) {
      throw new ValidationException("Imported media filename is invalid.");
    }
    String contentType = media.contentType().toLowerCase(Locale.ROOT);
    if (!uploadProperties.acceptedContentTypes().stream()
        .map(value -> value.toLowerCase(Locale.ROOT))
        .anyMatch(contentType::equals)) {
      throw new ValidationException("Imported media content type is not supported.");
    }
    String fileExtension = extension(filename);
    String matchingContentType =
        Map.of("mp4", "video/mp4", "mov", "video/quicktime", "webm", "video/webm")
            .get(fileExtension);
    if (!uploadProperties.acceptedExtensions().stream()
        .map(value -> value.toLowerCase(Locale.ROOT))
        .anyMatch(fileExtension::equals)) {
      throw new ValidationException("Imported media extension is not supported.");
    }
    if (matchingContentType == null || !matchingContentType.equals(contentType)) {
      throw new ValidationException("Imported media content type does not match its extension.");
    }
  }

  private static String extension(String filename) {
    int separator = filename.lastIndexOf('.');
    if (separator <= 0 || separator == filename.length() - 1) {
      throw new ValidationException("Imported media filename must include a supported extension.");
    }
    return filename.substring(separator + 1).toLowerCase(Locale.ROOT);
  }

  public record ImportedVideo(Video video, VideoImportProvenance provenance) {}
}
