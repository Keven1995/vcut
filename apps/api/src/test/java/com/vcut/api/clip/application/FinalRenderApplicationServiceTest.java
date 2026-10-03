package com.vcut.api.clip.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vcut.api.clip.domain.AspectRatio;
import com.vcut.api.clip.domain.CandidateStatus;
import com.vcut.api.clip.domain.CandidateVariant;
import com.vcut.api.clip.domain.CaptionPreset;
import com.vcut.api.clip.domain.Clip;
import com.vcut.api.clip.domain.ClipCandidate;
import com.vcut.api.clip.domain.ClipRender;
import com.vcut.api.clip.domain.ClipScore;
import com.vcut.api.clip.domain.ClipVersion;
import com.vcut.api.clip.domain.CropSettings;
import com.vcut.api.clip.domain.RenderStatus;
import com.vcut.api.job.application.OutboxRepository;
import com.vcut.api.shared.errors.ConflictException;
import com.vcut.api.shared.messaging.MessageEnvelope;
import com.vcut.api.shared.messaging.MessageKind;
import com.vcut.api.video.application.ObjectStorage;
import com.vcut.api.video.application.VideoRepository;
import com.vcut.api.video.domain.Video;
import com.vcut.api.video.domain.VideoUploadStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FinalRenderApplicationServiceTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final UUID videoId = UUID.randomUUID();
  private final UUID candidateId = UUID.randomUUID();
  private final UUID clipId = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-10-03T12:00:00Z");
  private ClipRepository clipRepository;
  private VideoRepository videoRepository;
  private ClipRenderRepository renderRepository;
  private OutboxRepository outboxRepository;
  private ObjectStorage objectStorage;
  private FinalRenderApplicationService service;
  private ClipAggregate aggregate;

  @BeforeEach
  void setUp() {
    clipRepository = mock(ClipRepository.class);
    videoRepository = mock(VideoRepository.class);
    renderRepository = mock(ClipRenderRepository.class);
    outboxRepository = mock(OutboxRepository.class);
    objectStorage = mock(ObjectStorage.class);
    aggregate = aggregate();
    when(clipRepository.findByIdForUser(clipId, userId)).thenReturn(Optional.of(aggregate));
    when(videoRepository.findByIdForUser(videoId, userId)).thenReturn(Optional.of(video()));
    service =
        new FinalRenderApplicationService(
            clipRepository,
            videoRepository,
            renderRepository,
            outboxRepository,
            objectStorage,
            new ObjectMapper().registerModule(new JavaTimeModule()),
            Clock.fixed(now, ZoneOffset.UTC));
  }

  @Test
  void publishesOnlyOneCommandForAnEditVersion() {
    ClipRender queued = ClipRender.queued(UUID.randomUUID(), clipId, userId, projectId, 1, now);
    when(renderRepository.findByClipAndVersionForUser(clipId, 1, userId))
        .thenReturn(Optional.empty(), Optional.of(queued));

    ClipRender first = service.request(userId, clipId, 1);
    ClipRender second = service.request(userId, clipId, 1);

    assertThat(first.status()).isEqualTo(com.vcut.api.clip.domain.RenderStatus.QUEUED);
    assertThat(second).isEqualTo(queued);
    verify(renderRepository).save(any(ClipRender.class));
    verify(outboxRepository).save(any());
  }

  @Test
  void rejectsAStaleEditVersionBeforeCreatingARender() {
    assertThatThrownBy(() -> service.request(userId, clipId, 2))
        .isInstanceOf(ConflictException.class);

    verify(renderRepository, never()).save(any());
  }

  @Test
  void acceptsOnlyServerOwnedFinalArtifactKeys() {
    ClipRender queued = ClipRender.queued(UUID.randomUUID(), clipId, userId, projectId, 1, now);
    when(renderRepository.findById(queued.id())).thenReturn(Optional.of(queued));
    String outputKey = ClipRenderObjectKeys.finalVideo(userId, projectId, clipId, 1);
    String thumbnailKey = ClipRenderObjectKeys.thumbnail(userId, projectId, clipId, 1);
    MessageEnvelope result =
        new MessageEnvelope(
            MessageKind.EVENT,
            UUID.randomUUID(),
            FinalRenderApplicationService.RESULT_EVENT_TYPE,
            1,
            queued.id(),
            queued.id(),
            FinalRenderApplicationService.OPERATION,
            1,
            UUID.randomUUID(),
            1,
            now,
            Map.of(
                "renderId",
                queued.id(),
                "clipId",
                clipId,
                "editVersion",
                1,
                "status",
                "READY",
                "outputObjectKey",
                outputKey,
                "thumbnailObjectKey",
                thumbnailKey,
                "durationSeconds",
                BigDecimal.valueOf(3),
                "width",
                1080,
                "height",
                1920,
                "aspectRatio",
                "9:16"));

    service.handleResult(result);

    verify(renderRepository).update(any(ClipRender.class));
  }

  @Test
  void signsOnlyAnOwnedReadyFinalRender() {
    ClipRender ready =
        new ClipRender(
            UUID.randomUUID(),
            clipId,
            userId,
            projectId,
            1,
            RenderStatus.READY,
            100,
            ClipRenderObjectKeys.finalVideo(userId, projectId, clipId, 1),
            ClipRenderObjectKeys.thumbnail(userId, projectId, clipId, 1),
            1080,
            1920,
            BigDecimal.valueOf(3),
            AspectRatio.PORTRAIT,
            null,
            null,
            now,
            now);
    when(renderRepository.findByIdForUser(ready.id(), userId)).thenReturn(Optional.of(ready));
    when(objectStorage.presignDownload(ready.outputObjectKey()))
        .thenReturn(new ObjectStorage.PresignedDownload("https://download", now.plusSeconds(60)));

    ObjectStorage.PresignedDownload result = service.downloadUrl(userId, ready.id());

    assertThat(result.url()).isEqualTo("https://download");
    verify(objectStorage).presignDownload(ready.outputObjectKey());
  }

  private ClipAggregate aggregate() {
    Clip clip =
        Clip.created(
            clipId, userId, projectId, videoId, candidateId, ClipScore.from(candidate()), now);
    ClipVersion version =
        ClipVersion.initial(
            UUID.randomUUID(),
            clip,
            BigDecimal.ONE,
            BigDecimal.valueOf(4),
            AspectRatio.PORTRAIT,
            CropSettings.centered(),
            CaptionPreset.MINIMAL,
            CaptionPreset.MINIMAL.defaultStyle(),
            List.of(),
            now);
    return new ClipAggregate(clip, version);
  }

  private ClipCandidate candidate() {
    return new ClipCandidate(
        candidateId,
        UUID.randomUUID(),
        videoId,
        userId,
        CandidateVariant.COMPLETE,
        CandidateStatus.SUGGESTED,
        BigDecimal.ONE,
        BigDecimal.valueOf(4),
        "group",
        "title",
        "description",
        "justification",
        BigDecimal.ONE,
        BigDecimal.ONE,
        BigDecimal.ONE,
        BigDecimal.ONE,
        BigDecimal.ONE,
        BigDecimal.ONE,
        now,
        now);
  }

  private Video video() {
    return new Video(
        videoId,
        userId,
        projectId,
        "users/" + userId + "/source.mp4",
        "source.mp4",
        "video/mp4",
        100,
        100L,
        null,
        BigDecimal.valueOf(10),
        1920,
        1080,
        BigDecimal.valueOf(30),
        true,
        VideoUploadStatus.READY,
        null,
        now,
        now);
  }
}
