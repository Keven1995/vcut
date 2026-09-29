package com.vcut.api.video.application;

import com.vcut.api.project.application.ProjectRepository;
import com.vcut.api.shared.errors.ResourceNotFoundException;
import com.vcut.api.shared.errors.ValidationException;
import com.vcut.api.video.domain.Video;
import com.vcut.api.video.domain.VideoUploadStatus;
import com.vcut.api.video.infrastructure.UploadProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnBean(ObjectStorage.class)
public class VideoApplicationService {

  private final ProjectRepository projectRepository;
  private final VideoRepository videoRepository;
  private final ObjectStorage objectStorage;
  private final UploadProperties uploadProperties;
  private final Clock clock;

  @Autowired
  public VideoApplicationService(
      ProjectRepository projectRepository,
      VideoRepository videoRepository,
      ObjectStorage objectStorage,
      UploadProperties uploadProperties) {
    this(projectRepository, videoRepository, objectStorage, uploadProperties, Clock.systemUTC());
  }

  VideoApplicationService(
      ProjectRepository projectRepository,
      VideoRepository videoRepository,
      ObjectStorage objectStorage,
      UploadProperties uploadProperties,
      Clock clock) {
    this.projectRepository = projectRepository;
    this.videoRepository = videoRepository;
    this.objectStorage = objectStorage;
    this.uploadProperties = uploadProperties;
    this.clock = clock;
  }

  @Transactional
  public UploadIntent createUploadIntent(
      UUID userId, UUID projectId, String originalFilename, String contentType, long sizeBytes) {
    projectRepository
        .findByIdForUser(projectId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("Project not found."));
    String filename = normalizeFilename(originalFilename);
    String normalizedContentType = normalizeContentType(contentType);
    String extension = extension(filename);
    validateUpload(filename, extension, normalizedContentType, sizeBytes);

    UUID videoId = UUID.randomUUID();
    String objectKey =
        "users/%s/projects/%s/source/%s/original.%s"
            .formatted(userId, projectId, videoId, extension);
    Instant now = clock.instant();
    Video video =
        Video.uploading(
            videoId, userId, projectId, objectKey, filename, normalizedContentType, sizeBytes, now);
    objectStorage.ensureBucket();
    videoRepository.save(video);
    return new UploadIntent(
        video, objectStorage.presignUpload(objectKey, normalizedContentType, sizeBytes));
  }

  @Transactional
  public Video confirmUpload(UUID userId, UUID videoId, String expectedChecksumSha256) {
    Video current = findOwned(userId, videoId);
    if (current.status() != VideoUploadStatus.UPLOADING) {
      return current;
    }

    ObjectStorage.StoredObject object =
        objectStorage
            .head(current.objectKey())
            .orElseThrow(() -> new ValidationException("Uploaded object was not found."));
    if (object.contentLength() != current.declaredSizeBytes()) {
      throw new ValidationException("Uploaded object size does not match the upload intent.");
    }
    if (object.contentType() != null
        && !object.contentType().isBlank()
        && !current.declaredContentType().equalsIgnoreCase(object.contentType())) {
      throw new ValidationException(
          "Uploaded object content type does not match the upload intent.");
    }
    if (expectedChecksumSha256 != null
        && !expectedChecksumSha256.equalsIgnoreCase(object.checksumSha256())) {
      throw new ValidationException("Uploaded object checksum does not match the confirmation.");
    }

    Video uploaded =
        current.uploaded(object.contentLength(), object.checksumSha256(), clock.instant());
    if (videoRepository.updateIfStatus(uploaded, VideoUploadStatus.UPLOADING)) {
      return uploaded;
    }
    return findOwned(userId, videoId);
  }

  @Transactional(readOnly = true)
  public Video get(UUID userId, UUID videoId) {
    return findOwned(userId, videoId);
  }

  @Transactional
  public void delete(UUID userId, UUID videoId) {
    Video video = findOwned(userId, videoId);
    objectStorage.delete(video.objectKey());
    videoRepository.delete(videoId, userId);
  }

  private Video findOwned(UUID userId, UUID videoId) {
    return videoRepository
        .findByIdForUser(videoId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("Video not found."));
  }

  private void validateUpload(
      String filename, String extension, String contentType, long sizeBytes) {
    if (sizeBytes <= 0 || sizeBytes > uploadProperties.maxFileSizeBytes()) {
      throw new ValidationException("Video size exceeds the configured upload limit.");
    }
    if (!uploadProperties.acceptedExtensions().stream()
        .map(value -> value.toLowerCase(Locale.ROOT))
        .anyMatch(extension::equals)) {
      throw new ValidationException("Video extension is not supported.");
    }
    if (!uploadProperties.acceptedContentTypes().stream()
        .map(value -> value.toLowerCase(Locale.ROOT))
        .anyMatch(contentType::equals)) {
      throw new ValidationException("Video content type is not supported.");
    }
  }

  private static String normalizeFilename(String filename) {
    if (filename == null
        || filename.isBlank()
        || filename.contains("/")
        || filename.contains("\\")) {
      throw new ValidationException("Video filename is invalid.");
    }
    String normalized = filename.trim();
    if (normalized.length() > 255) {
      throw new ValidationException("Video filename is too long.");
    }
    return normalized;
  }

  private static String normalizeContentType(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      throw new ValidationException("Video content type is required.");
    }
    return contentType.trim().toLowerCase(Locale.ROOT);
  }

  private static String extension(String filename) {
    int dot = filename.lastIndexOf('.');
    if (dot <= 0 || dot == filename.length() - 1) {
      throw new ValidationException("Video filename must have an extension.");
    }
    return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  public record UploadIntent(Video video, ObjectStorage.PresignedUpload upload) {}
}
