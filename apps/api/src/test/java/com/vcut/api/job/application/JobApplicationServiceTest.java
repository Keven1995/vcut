package com.vcut.api.job.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vcut.api.job.domain.Job;
import com.vcut.api.job.domain.JobStatus;
import com.vcut.api.job.domain.OutboxMessage;
import com.vcut.api.job.domain.Pipeline;
import com.vcut.api.job.domain.StageRun;
import com.vcut.api.job.domain.StageRunStatus;
import com.vcut.api.shared.correlation.CorrelationContext;
import com.vcut.api.shared.errors.ResourceNotFoundException;
import com.vcut.api.shared.messaging.MessageEnvelope;
import com.vcut.api.video.application.VideoRepository;
import com.vcut.api.video.domain.Video;
import com.vcut.api.video.domain.VideoUploadStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JobApplicationServiceTest {

  private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID VIDEO_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID CORRELATION_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

  private final VideoRepository videoRepository = mock(VideoRepository.class);
  private final PipelineRepository pipelineRepository = mock(PipelineRepository.class);
  private final JobRepository jobRepository = mock(JobRepository.class);
  private final StageRunRepository stageRunRepository = mock(StageRunRepository.class);
  private final OutboxRepository outboxRepository = mock(OutboxRepository.class);
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final JobApplicationService service =
      new JobApplicationService(
          videoRepository,
          pipelineRepository,
          jobRepository,
          stageRunRepository,
          outboxRepository,
          objectMapper,
          Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeEach
  void setUp() {
    CorrelationContext.set(CORRELATION_ID);
  }

  @AfterEach
  void tearDown() {
    CorrelationContext.clear();
  }

  @Test
  void createsPipelineJobStageAndOutboxInOneApplicationFlow() throws Exception {
    Video uploaded = uploadedVideo();
    when(jobRepository.findByIdempotencyKeyForUser(
            JobApplicationService.idempotencyKey(VIDEO_ID, 1), USER_ID))
        .thenReturn(Optional.empty());
    when(videoRepository.findByIdForUser(VIDEO_ID, USER_ID)).thenReturn(Optional.of(uploaded));
    when(videoRepository.updateIfStatus(any(Video.class), any(VideoUploadStatus.class)))
        .thenReturn(true);

    Job job = service.process(USER_ID, VIDEO_ID);

    assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
    assertThat(job.videoId()).isEqualTo(VIDEO_ID);
    verify(pipelineRepository).save(any(Pipeline.class));
    verify(jobRepository).save(job);
    var stageCaptor = org.mockito.ArgumentCaptor.forClass(StageRun.class);
    verify(stageRunRepository).save(stageCaptor.capture());
    assertThat(stageCaptor.getValue().pipelineVersion()).isEqualTo(job.version());
    assertThat(stageCaptor.getValue().inputPayload()).contains(job.id().toString());
    var captor = org.mockito.ArgumentCaptor.forClass(OutboxMessage.class);
    verify(outboxRepository).save(captor.capture());
    MessageEnvelope envelope =
        objectMapper.readValue(captor.getValue().payload(), MessageEnvelope.class);
    assertThat(envelope.jobId()).isEqualTo(job.id());
    assertThat(envelope.resourceId()).isEqualTo(VIDEO_ID);
    assertThat(envelope.correlationId()).isEqualTo(CORRELATION_ID);
    assertThat(envelope.data()).containsEntry("objectKey", uploaded.objectKey());
  }

  @Test
  void returnsExistingJobForDuplicateProcessRequest() {
    Job existing =
        Job.queued(
            UUID.randomUUID(),
            Pipeline.queued(
                UUID.randomUUID(), USER_ID, PROJECT_ID, VIDEO_ID, 1, CORRELATION_ID, NOW),
            JobApplicationService.OPERATION,
            JobApplicationService.idempotencyKey(VIDEO_ID, 1),
            JobApplicationService.STAGE,
            NOW);
    when(jobRepository.findByIdempotencyKeyForUser(existing.idempotencyKey(), USER_ID))
        .thenReturn(Optional.of(existing));

    assertThat(service.process(USER_ID, VIDEO_ID)).isEqualTo(existing);
    verifyNoInteractions(videoRepository, pipelineRepository, stageRunRepository, outboxRepository);
  }

  @Test
  void appliesWorkerResultAndCompletesJobAndPipeline() {
    Video validating = uploadedVideo().validating(NOW);
    Job job =
        Job.queued(
            UUID.randomUUID(),
            Pipeline.queued(
                UUID.randomUUID(), USER_ID, PROJECT_ID, VIDEO_ID, 1, CORRELATION_ID, NOW),
            JobApplicationService.OPERATION,
            JobApplicationService.idempotencyKey(VIDEO_ID, 1),
            JobApplicationService.STAGE,
            NOW);
    when(jobRepository.findById(job.id())).thenReturn(Optional.of(job));
    when(videoRepository.findByIdForUser(VIDEO_ID, USER_ID)).thenReturn(Optional.of(validating));
    MessageEnvelope result =
        new MessageEnvelope(
            com.vcut.api.shared.messaging.MessageKind.EVENT,
            UUID.randomUUID(),
            JobApplicationService.RESULT_EVENT_TYPE,
            1,
            job.id(),
            VIDEO_ID,
            JobApplicationService.OPERATION,
            1,
            CORRELATION_ID,
            1,
            NOW,
            Map.of(
                "status",
                "READY",
                "actualSizeBytes",
                100L,
                "durationSeconds",
                12.5,
                "width",
                1920,
                "height",
                1080,
                "frameRate",
                30.0,
                "hasAudio",
                true));

    service.handleResult(result);

    var videoCaptor = org.mockito.ArgumentCaptor.forClass(Video.class);
    verify(videoRepository)
        .updateValidation(videoCaptor.capture(), eq(VideoUploadStatus.VALIDATING));
    assertThat(videoCaptor.getValue().status()).isEqualTo(VideoUploadStatus.READY);
    verify(jobRepository)
        .complete(job.id(), JobStatus.COMPLETED, JobApplicationService.STAGE, 100, null, null, NOW);
    verify(pipelineRepository)
        .updateStatus(job.pipelineId(), com.vcut.api.job.domain.PipelineStatus.COMPLETED, NOW);
  }

  @Test
  void appliesWorkerStageUpdateBeforeFinalResult() {
    Job job =
        Job.queued(
            UUID.randomUUID(),
            Pipeline.queued(
                UUID.randomUUID(), USER_ID, PROJECT_ID, VIDEO_ID, 1, CORRELATION_ID, NOW),
            JobApplicationService.OPERATION,
            JobApplicationService.idempotencyKey(VIDEO_ID, 1),
            JobApplicationService.STAGE,
            NOW);
    when(jobRepository.findById(job.id())).thenReturn(Optional.of(job));
    MessageEnvelope update =
        new MessageEnvelope(
            com.vcut.api.shared.messaging.MessageKind.EVENT,
            UUID.randomUUID(),
            JobApplicationService.STAGE_UPDATE_EVENT_TYPE,
            1,
            job.id(),
            VIDEO_ID,
            JobApplicationService.OPERATION,
            1,
            CORRELATION_ID,
            1,
            NOW,
            Map.of("status", "PROCESSING", "progress", 25.0));

    service.handleStageUpdate(update);

    verify(stageRunRepository)
        .updateProgress(
            job.id(), JobApplicationService.STAGE, StageRunStatus.PROCESSING, 1, 25.0, NOW);
    verify(jobRepository)
        .updateProgress(job.id(), JobStatus.PROCESSING, JobApplicationService.STAGE, 1, 25.0, NOW);
  }

  @Test
  void doesNotExposeAJobToAnotherUser() {
    UUID otherUserId = UUID.fromString("99999999-9999-4999-8999-999999999999");
    UUID jobId = UUID.fromString("88888888-8888-4888-8888-888888888888");
    when(jobRepository.findByIdForUser(jobId, otherUserId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(otherUserId, jobId))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  private static Video uploadedVideo() {
    return Video.uploading(
            VIDEO_ID,
            USER_ID,
            PROJECT_ID,
            "users/user/projects/project/source/video/original.mp4",
            "video.mp4",
            "video/mp4",
            100,
            NOW)
        .uploaded(100, "checksum", NOW);
  }
}
