package com.vcut.api.clip.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vcut.api.clip.domain.AspectRatio;
import com.vcut.api.clip.domain.CandidateStatus;
import com.vcut.api.clip.domain.CandidateVariant;
import com.vcut.api.clip.domain.CaptionCue;
import com.vcut.api.clip.domain.CaptionPreset;
import com.vcut.api.clip.domain.Clip;
import com.vcut.api.clip.domain.ClipCandidate;
import com.vcut.api.clip.domain.ClipScore;
import com.vcut.api.clip.domain.ClipStatus;
import com.vcut.api.clip.domain.ClipVersion;
import com.vcut.api.job.application.OutboxRepository;
import com.vcut.api.job.domain.OutboxMessage;
import com.vcut.api.shared.messaging.MessageEnvelope;
import com.vcut.api.shared.messaging.MessageKind;
import com.vcut.api.transcription.application.TranscriptionRepository;
import com.vcut.api.transcription.domain.Transcription;
import com.vcut.api.transcription.domain.TranscriptionSegment;
import com.vcut.api.transcription.domain.TranscriptionStatus;
import com.vcut.api.transcription.domain.TranscriptionWord;
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
import org.mockito.ArgumentCaptor;

class ClipApplicationServiceTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID videoId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final UUID candidateId = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-10-02T12:00:00Z");
  private VideoRepository videoRepository;
  private ClipAnalysisRepository clipAnalysisRepository;
  private TranscriptionRepository transcriptionRepository;
  private ClipRepository clipRepository;
  private OutboxRepository outboxRepository;
  private ClipApplicationService service;

  @BeforeEach
  void setUp() {
    videoRepository = mock(VideoRepository.class);
    clipAnalysisRepository = mock(ClipAnalysisRepository.class);
    transcriptionRepository = mock(TranscriptionRepository.class);
    clipRepository = mock(ClipRepository.class);
    outboxRepository = mock(OutboxRepository.class);
    ObjectStorage objectStorage = mock(ObjectStorage.class);
    Video video = video();
    when(videoRepository.findByIdForUser(videoId, userId)).thenReturn(Optional.of(video));
    when(clipAnalysisRepository.findCandidateForUser(candidateId, userId))
        .thenReturn(Optional.of(candidate()));
    when(transcriptionRepository.findLatestForUser(videoId, userId))
        .thenReturn(Optional.of(transcription()));
    when(clipRepository.claimGeneration(any(), any(), any(Integer.class), any())).thenReturn(true);
    service =
        new ClipApplicationService(
            videoRepository,
            clipAnalysisRepository,
            transcriptionRepository,
            clipRepository,
            outboxRepository,
            objectStorage,
            new ObjectMapper().registerModule(new JavaTimeModule()),
            Clock.fixed(now, ZoneOffset.UTC));
  }

  @Test
  void createsClipWithRelativeWordCuesAndOwnedCandidate() {
    ClipAggregate aggregate = service.create(userId, videoId, candidateId, "9:16", "Minimal");

    assertThat(aggregate.clip().status()).isEqualTo(ClipStatus.QUEUED);
    assertThat(aggregate.version().aspectRatio()).isEqualTo(AspectRatio.PORTRAIT);
    assertThat(aggregate.version().captionPreset()).isEqualTo(CaptionPreset.MINIMAL);
    assertThat(aggregate.version().captionCues())
        .extracting("text")
        .containsExactly("Olá", "mundo");
    assertThat(aggregate.version().captionCues().get(0).startSeconds())
        .isEqualByComparingTo(BigDecimal.ZERO);
    verify(clipRepository).save(any(Clip.class));
    verify(clipRepository).saveVersion(any(ClipVersion.class));
  }

  @Test
  void rejectsCandidateFromAnotherVideo() {
    ClipCandidate otherVideoCandidate = candidateWithVideo(UUID.randomUUID());
    when(clipAnalysisRepository.findCandidateForUser(candidateId, userId))
        .thenReturn(Optional.of(otherVideoCandidate));

    assertThatThrownBy(() -> service.create(userId, videoId, candidateId, "16:9", "Bold"))
        .isInstanceOf(com.vcut.api.shared.errors.ResourceNotFoundException.class);
    verifyNoMoreInteractions(clipRepository);
  }

  @Test
  void passesPaginationAndStatusFilterToTheRepositoryAfterOwnershipCheck() {
    when(clipRepository.findByVideoForUser(videoId, userId, 2, 10, ClipStatus.PROCESSING))
        .thenReturn(new ClipPage(List.of(), 2, 10, 21));

    ClipPage result = service.list(userId, videoId, 2, 10, "processing");

    assertThat(result.totalElements()).isEqualTo(21);
    verify(clipRepository).findByVideoForUser(videoId, userId, 2, 10, ClipStatus.PROCESSING);
  }

  @Test
  void rejectsInvalidStyleWithoutCreatingANewVersion() {
    Clip clip =
        Clip.created(
            UUID.randomUUID(),
            userId,
            projectId,
            videoId,
            candidateId,
            ClipScore.from(candidate()),
            now);
    ClipVersion version =
        ClipVersion.initial(
            UUID.randomUUID(),
            clip,
            BigDecimal.TEN,
            BigDecimal.valueOf(13),
            AspectRatio.PORTRAIT,
            CaptionPreset.MINIMAL,
            CaptionPreset.MINIMAL.defaultStyle(),
            List.of(
                new CaptionCue(UUID.randomUUID(), "Olá, mundo!", BigDecimal.ZERO, BigDecimal.ONE)),
            now);
    when(clipRepository.findByIdForUser(clip.id(), userId))
        .thenReturn(Optional.of(new ClipAggregate(clip, version)));

    assertThatThrownBy(
            () ->
                service.update(
                    userId,
                    clip.id(),
                    new ClipEditCommand(
                        null, null, null, null, null, null, 7, null, null, null, null, null, null)))
        .isInstanceOf(com.vcut.api.shared.errors.ValidationException.class);
    verify(clipRepository).findByIdForUser(clip.id(), userId);
    org.mockito.Mockito.verify(clipRepository, org.mockito.Mockito.never()).saveVersion(any());
    org.mockito.Mockito.verify(clipRepository, org.mockito.Mockito.never()).update(any());
  }

  @Test
  void publishesOneGenerationCommandForAnEditVersion() throws Exception {
    Clip clip =
        Clip.created(
            UUID.randomUUID(),
            userId,
            projectId,
            videoId,
            candidateId,
            ClipScore.from(candidate()),
            now);
    ClipVersion version =
        ClipVersion.initial(
            UUID.randomUUID(),
            clip,
            BigDecimal.TEN,
            BigDecimal.valueOf(13),
            AspectRatio.PORTRAIT,
            CaptionPreset.MINIMAL,
            CaptionPreset.MINIMAL.defaultStyle(),
            List.of(
                new CaptionCue(UUID.randomUUID(), "Olá, mundo!", BigDecimal.ZERO, BigDecimal.ONE)),
            now);
    ClipAggregate aggregate = new ClipAggregate(clip, version);
    when(clipRepository.findByIdForUser(clip.id(), userId)).thenReturn(Optional.of(aggregate));
    when(clipRepository.claimGeneration(clip.id(), userId, 1, now)).thenReturn(true, false);

    service.generate(userId, clip.id());
    service.generate(userId, clip.id());

    ArgumentCaptor<OutboxMessage> outbox = ArgumentCaptor.forClass(OutboxMessage.class);
    verify(outboxRepository).save(outbox.capture());
    var data = new ObjectMapper().readTree(outbox.getValue().payload()).get("data");
    assertThat(data.get("captionPreset").asText()).isEqualTo("MINIMAL");
    assertThat(data.get("captionCues").get(0).get("sequence").asInt()).isZero();
    assertThat(data.get("captionStyle").get("fontFamily").asText())
        .isEqualTo(CaptionPreset.MINIMAL.defaultStyle().fontFamily());
  }

  @Test
  void acceptsGenericStageUpdatesFromTheWorkerEnvelope() {
    Clip clip =
        Clip.created(
            UUID.randomUUID(),
            userId,
            projectId,
            videoId,
            candidateId,
            ClipScore.from(candidate()),
            now);
    ClipVersion version =
        ClipVersion.initial(
            UUID.randomUUID(),
            clip,
            BigDecimal.TEN,
            BigDecimal.valueOf(13),
            AspectRatio.PORTRAIT,
            CaptionPreset.MINIMAL,
            CaptionPreset.MINIMAL.defaultStyle(),
            List.of(),
            now);
    when(clipRepository.findById(clip.id()))
        .thenReturn(Optional.of(new ClipAggregate(clip, version)));
    MessageEnvelope update =
        new MessageEnvelope(
            MessageKind.EVENT,
            UUID.randomUUID(),
            ClipApplicationService.STAGE_UPDATE_EVENT_TYPE,
            1,
            UUID.randomUUID(),
            clip.id(),
            ClipApplicationService.OPERATION,
            1,
            UUID.randomUUID(),
            1,
            now,
            Map.of("status", "PROCESSING", "progress", 25, "attempt", 1));

    service.handleStageUpdate(update);

    verify(clipRepository)
        .update(
            org.mockito.ArgumentMatchers.argThat(value -> value.status() == ClipStatus.PROCESSING));
  }

  @Test
  void ignoresWorkerEventForAnotherClip() {
    Clip clip =
        Clip.created(
            UUID.randomUUID(),
            userId,
            projectId,
            videoId,
            candidateId,
            ClipScore.from(candidate()),
            now);
    ClipVersion version =
        ClipVersion.initial(
            UUID.randomUUID(),
            clip,
            BigDecimal.TEN,
            BigDecimal.valueOf(13),
            AspectRatio.PORTRAIT,
            CaptionPreset.MINIMAL,
            CaptionPreset.MINIMAL.defaultStyle(),
            List.of(),
            now);
    when(clipRepository.findById(clip.id()))
        .thenReturn(Optional.of(new ClipAggregate(clip, version)));
    UUID anotherClip = UUID.randomUUID();
    MessageEnvelope result =
        new MessageEnvelope(
            MessageKind.EVENT,
            UUID.randomUUID(),
            ClipApplicationService.RESULT_EVENT_TYPE,
            1,
            UUID.randomUUID(),
            anotherClip,
            ClipApplicationService.OPERATION,
            1,
            UUID.randomUUID(),
            1,
            now,
            Map.of("clipId", anotherClip, "editVersion", 1, "status", "FAILED"));

    assertThatThrownBy(() -> service.handleResult(result))
        .isInstanceOf(com.vcut.api.shared.errors.ResourceNotFoundException.class);
  }

  @Test
  void persistsReadyResultOnlyForTheCurrentEditVersion() {
    Clip clip =
        Clip.created(
            UUID.randomUUID(),
            userId,
            projectId,
            videoId,
            candidateId,
            ClipScore.from(candidate()),
            now);
    ClipVersion version =
        ClipVersion.initial(
            UUID.randomUUID(),
            clip,
            BigDecimal.TEN,
            BigDecimal.valueOf(13),
            AspectRatio.PORTRAIT,
            CaptionPreset.MINIMAL,
            CaptionPreset.MINIMAL.defaultStyle(),
            List.of(),
            now);
    when(clipRepository.findById(clip.id()))
        .thenReturn(Optional.of(new ClipAggregate(clip, version)));
    String outputKey = ClipObjectKeys.preview(userId, projectId, clip.id(), 1);
    MessageEnvelope result =
        new MessageEnvelope(
            MessageKind.EVENT,
            UUID.randomUUID(),
            ClipApplicationService.RESULT_EVENT_TYPE,
            1,
            UUID.randomUUID(),
            clip.id(),
            ClipApplicationService.OPERATION,
            1,
            UUID.randomUUID(),
            1,
            now,
            Map.of(
                "clipId",
                clip.id(),
                "editVersion",
                1,
                "status",
                "READY",
                "outputObjectKey",
                outputKey,
                "durationSeconds",
                BigDecimal.valueOf(3),
                "width",
                1080,
                "height",
                1920,
                "aspectRatio",
                "9:16"));

    service.handleResult(result);

    verify(clipRepository)
        .update(
            org.mockito.ArgumentMatchers.argThat(
                value ->
                    value.status() == ClipStatus.READY
                        && outputKey.equals(value.outputObjectKey())
                        && value.outputEditVersion() == 1));
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
        BigDecimal.valueOf(120),
        1920,
        1080,
        BigDecimal.valueOf(30),
        true,
        VideoUploadStatus.READY,
        null,
        now,
        now);
  }

  private ClipCandidate candidate() {
    return candidateWithVideo(videoId);
  }

  private ClipCandidate candidateWithVideo(UUID candidateVideoId) {
    return new ClipCandidate(
        candidateId,
        UUID.randomUUID(),
        candidateVideoId,
        userId,
        CandidateVariant.COMPLETE,
        CandidateStatus.SUGGESTED,
        BigDecimal.TEN,
        BigDecimal.valueOf(13),
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

  private Transcription transcription() {
    return new Transcription(
        UUID.randomUUID(),
        videoId,
        userId,
        1,
        "fake",
        "pt-BR",
        "Olá mundo",
        BigDecimal.valueOf(120),
        BigDecimal.ONE,
        TranscriptionStatus.COMPLETED,
        List.of(
            new TranscriptionSegment(
                "Olá mundo",
                BigDecimal.TEN,
                BigDecimal.valueOf(13),
                BigDecimal.ONE,
                List.of(
                    new TranscriptionWord(
                        "Olá", BigDecimal.TEN, BigDecimal.valueOf(11), BigDecimal.ONE),
                    new TranscriptionWord(
                        "mundo", BigDecimal.valueOf(11), BigDecimal.valueOf(12), BigDecimal.ONE)))),
        null,
        null,
        now,
        now,
        now);
  }
}
