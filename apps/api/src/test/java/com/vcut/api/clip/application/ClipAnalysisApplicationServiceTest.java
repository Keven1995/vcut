package com.vcut.api.clip.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vcut.api.clip.domain.CandidateAction;
import com.vcut.api.clip.domain.CandidateStatus;
import com.vcut.api.clip.domain.ClipAnalysisRun;
import com.vcut.api.clip.domain.ClipCandidate;
import com.vcut.api.clip.domain.DurationPreference;
import com.vcut.api.job.application.OutboxRepository;
import com.vcut.api.job.domain.OutboxMessage;
import com.vcut.api.shared.messaging.MessageEnvelope;
import com.vcut.api.shared.messaging.MessageKind;
import com.vcut.api.transcription.application.TranscriptionRepository;
import com.vcut.api.transcription.domain.Transcription;
import com.vcut.api.transcription.domain.TranscriptionSegment;
import com.vcut.api.transcription.domain.TranscriptionStatus;
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

class ClipAnalysisApplicationServiceTest {

  private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID VIDEO_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID RUN_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID CANDIDATE_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

  private final VideoRepository videoRepository = mock(VideoRepository.class);
  private final TranscriptionRepository transcriptionRepository =
      mock(TranscriptionRepository.class);
  private final ClipAnalysisRepository clipAnalysisRepository = mock(ClipAnalysisRepository.class);
  private final OutboxRepository outboxRepository = mock(OutboxRepository.class);
  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
  private ClipAnalysisApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new ClipAnalysisApplicationService(
            videoRepository,
            transcriptionRepository,
            clipAnalysisRepository,
            outboxRepository,
            objectMapper,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void queuesVersionedCommandWithTimestampedTranscriptAndPreference() throws Exception {
    when(videoRepository.findByIdForUser(VIDEO_ID, USER_ID)).thenReturn(Optional.of(readyVideo()));
    when(transcriptionRepository.findLatestForUser(VIDEO_ID, USER_ID))
        .thenReturn(Optional.of(completedTranscription()));
    when(clipAnalysisRepository.nextVersion(VIDEO_ID)).thenReturn(2);

    ClipAnalysisRun run =
        service.request(USER_ID, VIDEO_ID, DurationPreference.CUSTOM, BigDecimal.valueOf(45));

    assertThat(run.pipelineVersion()).isEqualTo(2);
    ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
    verify(outboxRepository).save(captor.capture());
    JsonNode payload = objectMapper.readTree(captor.getValue().payload());
    assertThat(payload.get("eventType").asText())
        .isEqualTo(ClipAnalysisApplicationService.COMMAND_EVENT_TYPE);
    assertThat(payload.get("operation").asText()).isEqualTo("CLIP_ANALYSIS");
    assertThat(payload.get("data").get("durationPreference").asText()).isEqualTo("CUSTOM");
    assertThat(payload.get("data").get("durationSeconds").asInt()).isEqualTo(10);
    assertThat(payload.get("data").get("objectKey").asText()).isEqualTo(readyVideo().objectKey());
    assertThat(payload.get("data").get("segments")).hasSize(1);
    assertThat(payload.get("data").get("segments").get(0).get("startSeconds").asInt()).isZero();
  }

  @Test
  void rejectsUnknownCandidateFieldsAndInvalidIntervalsBeforePersistence() {
    ClipAnalysisRun run = queuedRun();
    when(clipAnalysisRepository.findRun(VIDEO_ID, 1)).thenReturn(Optional.of(run));
    when(videoRepository.findByIdForUser(VIDEO_ID, USER_ID)).thenReturn(Optional.of(readyVideo()));
    MessageEnvelope result =
        result(
            Map.of(
                "status",
                "COMPLETED",
                "candidates",
                List.of(
                    Map.ofEntries(
                        Map.entry("candidateId", CANDIDATE_ID),
                        Map.entry("variant", "SHORT"),
                        Map.entry("start", BigDecimal.ZERO),
                        Map.entry("end", BigDecimal.valueOf(91)),
                        Map.entry("group", "moment-1"),
                        Map.entry("title", "Title"),
                        Map.entry("description", "Description"),
                        Map.entry("justification", "Reason"),
                        Map.entry("unexpected", "reject")))));

    assertThatThrownBy(() -> service.handleResult(result))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void recordsOwnedCandidateActionAndRefusesCandidateFromAnotherUser() {
    ClipCandidate candidate = candidate(CandidateStatus.SUGGESTED);
    when(clipAnalysisRepository.findCandidateForUser(CANDIDATE_ID, USER_ID))
        .thenReturn(Optional.of(candidate));

    ClipCandidate accepted = service.accept(USER_ID, CANDIDATE_ID);

    assertThat(accepted.status()).isEqualTo(CandidateStatus.ACCEPTED);
    verify(clipAnalysisRepository)
        .recordAction(CANDIDATE_ID, VIDEO_ID, USER_ID, CandidateAction.ACCEPT, NOW);
    verify(clipAnalysisRepository)
        .updateCandidateStatus(CANDIDATE_ID, USER_ID, CandidateStatus.ACCEPTED, NOW);
  }

  @Test
  void doesNotRecordAnActionWhenCandidateIsNotOwned() {
    when(clipAnalysisRepository.findCandidateForUser(CANDIDATE_ID, USER_ID))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.discard(USER_ID, CANDIDATE_ID))
        .isInstanceOf(com.vcut.api.shared.errors.ResourceNotFoundException.class);
  }

  @Test
  void rejectsMalformedWorkerRunIdentity() {
    ClipAnalysisRun run = queuedRun();
    when(clipAnalysisRepository.findRun(VIDEO_ID, 1)).thenReturn(Optional.of(run));
    MessageEnvelope result =
        result(
            Map.of(
                "status", "FAILED",
                "runId", UUID.randomUUID(),
                "candidates", List.of()));

    assertThatThrownBy(() -> service.handleResult(result))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void persistsOnlyValidatedWorkerCandidates() {
    ClipAnalysisRun run = queuedRun();
    when(clipAnalysisRepository.findRun(VIDEO_ID, 1)).thenReturn(Optional.of(run));
    when(videoRepository.findByIdForUser(VIDEO_ID, USER_ID)).thenReturn(Optional.of(readyVideo()));
    MessageEnvelope result =
        result(
            Map.of(
                "provider",
                "deterministic",
                "durationSeconds",
                BigDecimal.TEN,
                "hasReliableCandidate",
                true,
                "candidates",
                List.of(
                    Map.ofEntries(
                        Map.entry("id", CANDIDATE_ID),
                        Map.entry("groupId", UUID.randomUUID()),
                        Map.entry("videoId", VIDEO_ID),
                        Map.entry("pipelineVersion", 1),
                        Map.entry("variant", "SHORT"),
                        Map.entry("startSeconds", BigDecimal.ONE),
                        Map.entry("endSeconds", BigDecimal.valueOf(4)),
                        Map.entry("title", "Title"),
                        Map.entry("description", "Description"),
                        Map.entry("justification", "Reason"),
                        Map.entry(
                            "scores",
                            Map.of(
                                "hook", BigDecimal.valueOf(0.8),
                                "context", BigDecimal.valueOf(0.7),
                                "development", BigDecimal.valueOf(0.6),
                                "payoff", BigDecimal.valueOf(0.9),
                                "independence", BigDecimal.valueOf(0.8),
                                "engagement", BigDecimal.valueOf(0.7)))))));

    service.handleResult(result);

    ArgumentCaptor<List<ClipCandidate>> candidates = ArgumentCaptor.forClass(List.class);
    verify(clipAnalysisRepository).saveCandidates(candidates.capture());
    assertThat(candidates.getValue())
        .singleElement()
        .satisfies(value -> assertThat(value.id()).isEqualTo(CANDIDATE_ID));
    verify(clipAnalysisRepository)
        .updateRunStatus(
            RUN_ID, com.vcut.api.clip.domain.AnalysisRunStatus.COMPLETED, null, null, NOW);
  }

  private static ClipAnalysisRun queuedRun() {
    return ClipAnalysisRun.queued(
        RUN_ID, VIDEO_ID, USER_ID, 1, DurationPreference.AUTO, null, null, "en", NOW);
  }

  private static MessageEnvelope result(Map<String, Object> data) {
    return new MessageEnvelope(
        MessageKind.EVENT,
        UUID.randomUUID(),
        ClipAnalysisApplicationService.RESULT_EVENT_TYPE,
        1,
        RUN_ID,
        VIDEO_ID,
        ClipAnalysisApplicationService.OPERATION,
        1,
        UUID.randomUUID(),
        1,
        NOW,
        data);
  }

  private static ClipCandidate candidate(CandidateStatus status) {
    return new ClipCandidate(
        CANDIDATE_ID,
        RUN_ID,
        VIDEO_ID,
        USER_ID,
        com.vcut.api.clip.domain.CandidateVariant.SHORT,
        status,
        BigDecimal.ONE,
        BigDecimal.TEN,
        "moment-1",
        "Title",
        "Description",
        "Reason",
        BigDecimal.valueOf(0.8),
        BigDecimal.valueOf(0.7),
        BigDecimal.valueOf(0.6),
        BigDecimal.valueOf(0.9),
        BigDecimal.valueOf(0.8),
        BigDecimal.valueOf(0.7),
        NOW,
        NOW);
  }

  private static Transcription completedTranscription() {
    return new Transcription(
        UUID.randomUUID(),
        VIDEO_ID,
        USER_ID,
        1,
        "fake",
        "en",
        "hello world",
        BigDecimal.TEN,
        BigDecimal.ONE,
        TranscriptionStatus.COMPLETED,
        List.of(
            new TranscriptionSegment(
                "hello world", BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, List.of())),
        null,
        null,
        NOW,
        NOW,
        NOW);
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
            BigDecimal.TEN,
            1280,
            720,
            BigDecimal.valueOf(30),
            true,
            null,
            NOW);
  }
}
