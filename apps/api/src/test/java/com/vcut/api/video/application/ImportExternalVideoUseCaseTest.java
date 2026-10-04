package com.vcut.api.video.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vcut.api.project.application.ProjectRepository;
import com.vcut.api.project.domain.Project;
import com.vcut.api.project.domain.ProjectStatus;
import com.vcut.api.shared.errors.ValidationException;
import com.vcut.api.video.domain.Video;
import com.vcut.api.video.domain.VideoImportProvenance;
import com.vcut.api.video.domain.VideoUploadStatus;
import com.vcut.api.video.infrastructure.ExternalVideoImportProperties;
import com.vcut.api.video.infrastructure.FixtureLocalVideoImporter;
import com.vcut.api.video.infrastructure.UploadProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImportExternalVideoUseCaseTest {

  private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

  private final ProjectRepository projects = mock(ProjectRepository.class);
  private final VideoRepository videos = mock(VideoRepository.class);
  private final VideoImportProvenanceRepository provenanceRepository =
      mock(VideoImportProvenanceRepository.class);
  private final FixtureStorage storage = new FixtureStorage();
  private final VideoImportCancellationRegistry cancellations =
      new VideoImportCancellationRegistry();
  private final VideoImportDeadLetterRepository deadLetters =
      mock(VideoImportDeadLetterRepository.class);
  private ImportExternalVideoUseCase useCase;

  @BeforeEach
  void setUp() {
    when(projects.findByIdForUser(PROJECT_ID, USER_ID))
        .thenReturn(
            Optional.of(
                new Project(PROJECT_ID, USER_ID, "Project", ProjectStatus.ACTIVE, NOW, NOW)));
    var limits =
        new ExternalVideoImporter.ImportLimits(
            1_000_000, Duration.ofHours(2), Duration.ofMinutes(1));
    var validator =
        new ValidateExternalVideoImportUseCase(
            projects,
            List.of(new FixtureLocalVideoImporter()),
            limits,
            Clock.fixed(NOW, ZoneOffset.UTC));
    var runner =
        new ResilientExternalVideoImportRunner(
            new ExternalVideoImportProperties(
                false,
                true,
                1_000_000,
                Duration.ofHours(2),
                Duration.ofMinutes(1),
                2,
                Duration.ZERO),
            deadLetters);
    useCase =
        new ImportExternalVideoUseCase(
            validator,
            videos,
            provenanceRepository,
            storage,
            new UploadProperties(1_000_000, 7_200, List.of("mp4"), List.of("video/mp4")),
            cancellations,
            runner,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void storesImportedVideoAsUploadedAndPersistsSanitizedProvenance() {
    var result = useCase.execute(USER_ID, PROJECT_ID, request());

    assertThat(result.video().status()).isEqualTo(VideoUploadStatus.UPLOADED);
    assertThat(result.video().objectKey())
        .matches("users/[^/]+/projects/[^/]+/source/[^/]+/original\\.mp4");
    assertThat(storage.content).isEqualTo("VCUT-LOCAL-IMPORT-FIXTURE-v1\n");
    assertThat(storage.ensureBucketCalls).isEqualTo(1);
    verify(videos).save(any(Video.class));
    verify(provenanceRepository).save(any(UUID.class), any(VideoImportProvenance.class));
  }

  @Test
  void removesStoredObjectIfTheStorageMetadataDoesNotMatchTheImportedFile() {
    storage.reportWrongSize = true;

    assertThatThrownBy(() -> useCase.execute(USER_ID, PROJECT_ID, request()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("metadata did not match");

    assertThat(storage.deletedKeys).hasSize(1);
  }

  private static ValidateExternalVideoImportUseCase.ImportRequest request() {
    return new ValidateExternalVideoImportUseCase.ImportRequest(
        UUID.randomUUID(),
        "fixture-local",
        "fixture://sample-video",
        "sample-video",
        "test-consent-v1",
        true);
  }

  private static final class FixtureStorage implements ExternalVideoImportStorage {
    private String content = "";
    private final java.util.ArrayList<String> deletedKeys = new java.util.ArrayList<>();
    private int ensureBucketCalls;
    private boolean reportWrongSize;

    @Override
    public void ensureBucket() {
      ensureBucketCalls++;
    }

    @Override
    public ObjectStorage.StoredObject upload(
        Path source, String objectKey, String contentType, long contentLength) {
      try {
        content = Files.readString(source);
      } catch (IOException exception) {
        throw new IllegalStateException(exception);
      }
      return new ObjectStorage.StoredObject(
          objectKey,
          reportWrongSize ? contentLength + 1 : contentLength,
          contentType,
          "etag",
          "checksum");
    }

    @Override
    public void delete(String objectKey) {
      deletedKeys.add(objectKey);
    }
  }
}
