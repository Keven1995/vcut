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
import com.vcut.api.usage.application.UsageApplicationService;
import com.vcut.api.video.domain.Video;
import com.vcut.api.video.domain.VideoUploadStatus;
import com.vcut.api.video.infrastructure.UploadProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VideoApplicationServiceTest {

  private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

  private final ProjectRepository projectRepository = mock(ProjectRepository.class);
  private final VideoRepository videoRepository = mock(VideoRepository.class);
  private final UsageApplicationService usageApplicationService =
      mock(UsageApplicationService.class);
  private final StorageStub storage = new StorageStub();
  private VideoApplicationService service;

  @BeforeEach
  void setUp() {
    when(projectRepository.findByIdForUser(PROJECT_ID, USER_ID))
        .thenReturn(
            Optional.of(
                new Project(PROJECT_ID, USER_ID, "Podcast", ProjectStatus.ACTIVE, NOW, NOW)));
    service =
        new VideoApplicationService(
            projectRepository,
            videoRepository,
            storage,
            new UploadProperties(1_000, 7_200, List.of("mp4"), List.of("video/mp4")),
            usageApplicationService,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void createsScopedObjectKeyAndPresignedUpload() {
    var intent = service.createUploadIntent(USER_ID, PROJECT_ID, "episode.mp4", "video/mp4", 100);

    assertThat(intent.video().status()).isEqualTo(VideoUploadStatus.UPLOADING);
    assertThat(intent.video().objectKey())
        .matches("users/[^/]+/projects/[^/]+/source/[^/]+/original\\.mp4");
    assertThat(intent.upload().url()).isEqualTo("http://storage/upload");
    verify(videoRepository).save(any(Video.class));
    verify(usageApplicationService).assertUploadAllowed(USER_ID, 100);
  }

  @Test
  void rejectsUnsupportedContentTypeBeforePersistingIntent() {
    assertThatThrownBy(
            () -> service.createUploadIntent(USER_ID, PROJECT_ID, "episode.mp4", "video/avi", 100))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void confirmsMatchingObjectAndReturnsSameStateForDuplicateConfirmation() {
    Video uploading =
        Video.uploading(
            UUID.randomUUID(),
            USER_ID,
            PROJECT_ID,
            "users/user/projects/project/source/video/original.mp4",
            "episode.mp4",
            "video/mp4",
            100,
            NOW);
    storage.object =
        new ObjectStorage.StoredObject(uploading.objectKey(), 100, "video/mp4", "etag", "checksum");
    when(videoRepository.findByIdForUser(uploading.id(), USER_ID))
        .thenReturn(Optional.of(uploading));
    when(videoRepository.updateIfStatus(any(Video.class), any(VideoUploadStatus.class)))
        .thenReturn(true);

    Video confirmed = service.confirmUpload(USER_ID, uploading.id(), null);

    assertThat(confirmed.status()).isEqualTo(VideoUploadStatus.UPLOADED);
    assertThat(confirmed.actualSizeBytes()).isEqualTo(100);
    verify(videoRepository).updateIfStatus(any(Video.class), any(VideoUploadStatus.class));
  }

  @Test
  void duplicateConfirmationReturnsExistingUploadedStateWithoutReadingStorage() {
    Video uploading =
        Video.uploading(
            UUID.randomUUID(),
            USER_ID,
            PROJECT_ID,
            "users/user/projects/project/source/video/original.mp4",
            "episode.mp4",
            "video/mp4",
            100,
            NOW);
    Video uploaded = uploading.uploaded(100, "checksum", NOW.plusSeconds(1));
    when(videoRepository.findByIdForUser(uploading.id(), USER_ID))
        .thenReturn(Optional.of(uploaded));

    Video result = service.confirmUpload(USER_ID, uploading.id(), null);

    assertThat(result).isEqualTo(uploaded);
    assertThat(storage.object).isNull();
  }

  @Test
  void rejectsConfirmationWhenExpectedChecksumDoesNotMatchObject() {
    Video uploading =
        Video.uploading(
            UUID.randomUUID(),
            USER_ID,
            PROJECT_ID,
            "users/user/projects/project/source/video/original.mp4",
            "episode.mp4",
            "video/mp4",
            100,
            NOW);
    storage.object =
        new ObjectStorage.StoredObject(uploading.objectKey(), 100, "video/mp4", "etag", "actual");
    when(videoRepository.findByIdForUser(uploading.id(), USER_ID))
        .thenReturn(Optional.of(uploading));

    assertThatThrownBy(() -> service.confirmUpload(USER_ID, uploading.id(), "expected"))
        .isInstanceOf(ValidationException.class);
  }

  private static final class StorageStub implements ObjectStorage {
    private StoredObject object;

    @Override
    public void ensureBucket() {}

    @Override
    public PresignedUpload presignUpload(String objectKey, String contentType, long contentLength) {
      return new PresignedUpload("http://storage/upload", NOW.plusSeconds(900));
    }

    @Override
    public PresignedDownload presignDownload(String objectKey) {
      return new PresignedDownload("http://storage/download", NOW.plusSeconds(900));
    }

    @Override
    public Optional<StoredObject> head(String objectKey) {
      return Optional.ofNullable(object);
    }

    @Override
    public java.io.InputStream read(String objectKey) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void delete(String objectKey) {}
  }
}
