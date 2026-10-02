package com.vcut.api.transcription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vcut.api.job.application.OutboxRepository;
import com.vcut.api.job.domain.OutboxMessage;
import com.vcut.api.shared.errors.ResourceNotFoundException;
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
import org.mockito.ArgumentCaptor;

class TranscriptionApplicationServiceTest {

  private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID VIDEO_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

  private final VideoRepository videoRepository = mock(VideoRepository.class);
  private final ObjectStorage objectStorage = mock(ObjectStorage.class);
  private final TranscriptionRepository transcriptionRepository =
      mock(TranscriptionRepository.class);
  private final OutboxRepository outboxRepository = mock(OutboxRepository.class);
  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
  private TranscriptionApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new TranscriptionApplicationService(
            videoRepository,
            objectStorage,
            transcriptionRepository,
            outboxRepository,
            objectMapper,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void queuesVersionedTranscriptionCommandForReadyOwnedVideo() throws Exception {
    when(videoRepository.findByIdForUser(VIDEO_ID, USER_ID)).thenReturn(Optional.of(readyVideo()));
    when(transcriptionRepository.nextVersion(VIDEO_ID)).thenReturn(2);

    var queued = service.request(USER_ID, VIDEO_ID, "pt-BR");

    assertThat(queued.status())
        .isEqualTo(com.vcut.api.transcription.domain.TranscriptionStatus.QUEUED);
    assertThat(queued.pipelineVersion()).isEqualTo(2);
    ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
    verify(outboxRepository).save(captor.capture());
    MessageEnvelope command =
        objectMapper.readValue(captor.getValue().payload(), MessageEnvelope.class);
    assertThat(command.eventType()).isEqualTo(TranscriptionApplicationService.COMMAND_EVENT_TYPE);
    assertThat(command.resourceId()).isEqualTo(VIDEO_ID);
    assertThat(command.version()).isEqualTo(2);
    assertThat(command.data().get("language")).isEqualTo("pt-BR");
    assertThat(command.data().get("audioObjectKey").toString())
        .isEqualTo(
            "users/11111111-1111-4111-8111-111111111111/projects/22222222-2222-4222-8222-222222222222/audio/33333333-3333-4333-8333-333333333333/v2/transcription.wav");
  }

  @Test
  void rejectsTranscriptionWhenVideoIsNotOwned() {
    when(videoRepository.findByIdForUser(VIDEO_ID, USER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.request(USER_ID, VIDEO_ID, null))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void completesTranscriptionFromValidatedWorkerEvent() {
    var queued =
        com.vcut.api.transcription.domain.Transcription.queued(
            UUID.randomUUID(), VIDEO_ID, USER_ID, 1, "fake", "en", NOW);
    when(transcriptionRepository.findByVideoAndVersion(VIDEO_ID, 1))
        .thenReturn(Optional.of(queued));
    MessageEnvelope result =
        envelope(
            TranscriptionApplicationService.RESULT_EVENT_TYPE,
            Map.of(
                "videoId",
                VIDEO_ID,
                "pipelineVersion",
                1,
                "provider",
                "fake",
                "language",
                "en",
                "text",
                "hello",
                "durationSeconds",
                BigDecimal.ONE,
                "confidence",
                BigDecimal.valueOf(0.9),
                "segments",
                List.of(
                    Map.of(
                        "text",
                        "hello",
                        "start",
                        BigDecimal.ZERO,
                        "end",
                        BigDecimal.ONE,
                        "confidence",
                        BigDecimal.valueOf(0.9),
                        "words",
                        List.of(
                            Map.of(
                                "text",
                                "hello",
                                "start",
                                BigDecimal.ZERO,
                                "end",
                                BigDecimal.ONE,
                                "confidence",
                                BigDecimal.valueOf(0.9)))))));

    service.handleResult(result);

    ArgumentCaptor<com.vcut.api.transcription.domain.Transcription> captor =
        ArgumentCaptor.forClass(com.vcut.api.transcription.domain.Transcription.class);
    verify(transcriptionRepository).complete(captor.capture());
    assertThat(captor.getValue().status())
        .isEqualTo(com.vcut.api.transcription.domain.TranscriptionStatus.COMPLETED);
    assertThat(captor.getValue().segments()).hasSize(1);
    assertThat(captor.getValue().segments().getFirst().words()).hasSize(1);
  }

  @Test
  void returnsAuthorizedAudioUrlForLatestTranscription() {
    var transcription =
        new com.vcut.api.transcription.domain.Transcription(
            UUID.randomUUID(),
            VIDEO_ID,
            USER_ID,
            3,
            "fake",
            "en",
            "hello",
            BigDecimal.ONE,
            BigDecimal.valueOf(0.9),
            com.vcut.api.transcription.domain.TranscriptionStatus.COMPLETED,
            List.of(),
            null,
            null,
            NOW,
            NOW,
            NOW);
    when(videoRepository.findByIdForUser(VIDEO_ID, USER_ID)).thenReturn(Optional.of(readyVideo()));
    when(transcriptionRepository.findLatestForUser(VIDEO_ID, USER_ID))
        .thenReturn(Optional.of(transcription));
    when(objectStorage.presignDownload(any()))
        .thenReturn(
            new ObjectStorage.PresignedDownload("https://storage/audio", NOW.plusSeconds(900)));

    var download = service.audioUrl(USER_ID, VIDEO_ID);

    assertThat(download.url()).isEqualTo("https://storage/audio");
    verify(objectStorage)
        .presignDownload(
            "users/11111111-1111-4111-8111-111111111111/projects/22222222-2222-4222-8222-222222222222/audio/33333333-3333-4333-8333-333333333333/v3/transcription.wav");
  }

  private static Video readyVideo() {
    return Video.uploading(
            VIDEO_ID,
            USER_ID,
            PROJECT_ID,
            "users/user/projects/project/source/video/original.mp4",
            "episode.mp4",
            "video/mp4",
            100,
            NOW)
        .uploaded(100, "checksum", NOW)
        .validating(NOW)
        .validated(
            VideoUploadStatus.READY,
            100L,
            BigDecimal.ONE,
            1280,
            720,
            BigDecimal.valueOf(30),
            true,
            null,
            NOW);
  }

  private static MessageEnvelope envelope(String eventType, Map<String, Object> data) {
    return new MessageEnvelope(
        MessageKind.EVENT,
        UUID.randomUUID(),
        eventType,
        1,
        UUID.randomUUID(),
        VIDEO_ID,
        TranscriptionApplicationService.OPERATION,
        1,
        UUID.randomUUID(),
        1,
        NOW,
        data);
  }
}
