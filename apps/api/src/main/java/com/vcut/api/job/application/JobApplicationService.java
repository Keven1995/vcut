package com.vcut.api.job.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vcut.api.job.domain.Job;
import com.vcut.api.job.domain.JobStatus;
import com.vcut.api.job.domain.OutboxMessage;
import com.vcut.api.job.domain.Pipeline;
import com.vcut.api.job.domain.PipelineStatus;
import com.vcut.api.job.domain.StageRun;
import com.vcut.api.job.domain.StageRunStatus;
import com.vcut.api.shared.correlation.CorrelationContext;
import com.vcut.api.shared.errors.ConflictException;
import com.vcut.api.shared.errors.ProcessingException;
import com.vcut.api.shared.errors.ResourceNotFoundException;
import com.vcut.api.shared.messaging.MessageEnvelope;
import com.vcut.api.shared.messaging.MessageKind;
import com.vcut.api.video.application.VideoRepository;
import com.vcut.api.video.domain.Video;
import com.vcut.api.video.domain.VideoUploadStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobApplicationService {

  public static final String OPERATION = "VIDEO_VALIDATION";
  public static final String STAGE = "INGEST";
  public static final String COMMAND_EVENT_TYPE = "VideoValidationRequested";
  public static final String RESULT_EVENT_TYPE = "VideoValidationCompleted";
  public static final String STAGE_UPDATE_EVENT_TYPE = "StageRunUpdated";
  public static final String COMMAND_EXCHANGE = "vcut.pipeline.commands";
  public static final String COMMAND_ROUTING_KEY = "pipeline.video.validate";

  private final VideoRepository videoRepository;
  private final PipelineRepository pipelineRepository;
  private final JobRepository jobRepository;
  private final StageRunRepository stageRunRepository;
  private final OutboxRepository outboxRepository;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  @Autowired
  public JobApplicationService(
      VideoRepository videoRepository,
      PipelineRepository pipelineRepository,
      JobRepository jobRepository,
      StageRunRepository stageRunRepository,
      OutboxRepository outboxRepository,
      ObjectMapper objectMapper) {
    this(
        videoRepository,
        pipelineRepository,
        jobRepository,
        stageRunRepository,
        outboxRepository,
        objectMapper,
        Clock.systemUTC());
  }

  JobApplicationService(
      VideoRepository videoRepository,
      PipelineRepository pipelineRepository,
      JobRepository jobRepository,
      StageRunRepository stageRunRepository,
      OutboxRepository outboxRepository,
      ObjectMapper objectMapper,
      Clock clock) {
    this.videoRepository = videoRepository;
    this.pipelineRepository = pipelineRepository;
    this.jobRepository = jobRepository;
    this.stageRunRepository = stageRunRepository;
    this.outboxRepository = outboxRepository;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional
  public Job process(UUID userId, UUID videoId) {
    String idempotencyKey = idempotencyKey(videoId, 1);
    var existing = jobRepository.findByIdempotencyKeyForUser(idempotencyKey, userId);
    if (existing.isPresent()) {
      return existing.get();
    }

    Video video = ownedVideo(userId, videoId);
    if (video.status() != VideoUploadStatus.UPLOADED) {
      throw new ConflictException("Video is not ready to start processing.");
    }

    Instant now = clock.instant();
    UUID correlationId = CorrelationContext.current().orElseGet(UUID::randomUUID);
    Pipeline pipeline =
        Pipeline.queued(
            UUID.randomUUID(), userId, video.projectId(), video.id(), 1, correlationId, now);
    Job job = Job.queued(UUID.randomUUID(), pipeline, OPERATION, idempotencyKey, STAGE, now);
    Map<String, Object> commandData =
        Map.of(
            "videoId", video.id(),
            "objectKey", video.objectKey(),
            "originalFilename", video.originalFilename(),
            "declaredContentType", video.declaredContentType(),
            "declaredSizeBytes", video.declaredSizeBytes());
    MessageEnvelope command =
        new MessageEnvelope(
            MessageKind.COMMAND,
            UUID.randomUUID(),
            COMMAND_EVENT_TYPE,
            1,
            job.id(),
            video.id(),
            OPERATION,
            1,
            correlationId,
            1,
            now,
            commandData);
    String commandPayload = serialize(command);
    StageRun stageRun = StageRun.queued(UUID.randomUUID(), job.id(), STAGE, commandPayload, now);
    OutboxMessage outbox =
        new OutboxMessage(
            UUID.randomUUID(),
            "JOB",
            job.id(),
            COMMAND_EVENT_TYPE,
            COMMAND_ROUTING_KEY,
            commandPayload,
            0,
            now,
            null,
            null,
            now);

    if (!videoRepository.updateIfStatus(video.validating(now), VideoUploadStatus.UPLOADED)) {
      return jobRepository
          .findByIdempotencyKeyForUser(idempotencyKey, userId)
          .orElseThrow(() -> new ConflictException("Video processing is already being started."));
    }
    pipelineRepository.save(pipeline);
    jobRepository.save(job);
    stageRunRepository.save(stageRun);
    outboxRepository.save(outbox);
    return job;
  }

  @Transactional(readOnly = true)
  public Job get(UUID userId, UUID jobId) {
    return jobRepository
        .findByIdForUser(jobId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("Job not found."));
  }

  @Transactional
  public void handleStageUpdate(MessageEnvelope update) {
    if (!STAGE_UPDATE_EVENT_TYPE.equals(update.eventType()) || update.kind() != MessageKind.EVENT) {
      throw new IllegalArgumentException("Unsupported worker stage update event.");
    }
    Job job =
        jobRepository
            .findById(update.jobId())
            .orElseThrow(() -> new ResourceNotFoundException("Job for stage update not found."));
    if (!job.videoId().equals(update.resourceId())
        || !OPERATION.equals(update.operation())
        || job.version() != update.version()) {
      throw new IllegalArgumentException("Worker stage update does not match the job contract.");
    }
    if (job.status() == JobStatus.COMPLETED
        || job.status() == JobStatus.FAILED
        || job.status() == JobStatus.CANCELLED) {
      return;
    }
    StageRunStatus stageStatus = stageStatus(update.data());
    double progress = progress(update.data().get("progress"));
    stageRunRepository.updateProgress(
        job.id(), STAGE, stageStatus, update.attempt(), progress, update.occurredAt());
    jobRepository.updateProgress(
        job.id(), JobStatus.PROCESSING, STAGE, update.attempt(), progress, update.occurredAt());
  }

  @Transactional
  public void handleResult(MessageEnvelope result) {
    if (!RESULT_EVENT_TYPE.equals(result.eventType()) || result.kind() != MessageKind.EVENT) {
      throw new IllegalArgumentException("Unsupported worker result event.");
    }
    Job job =
        jobRepository
            .findById(result.jobId())
            .orElseThrow(() -> new ResourceNotFoundException("Job for worker result not found."));
    if (!job.videoId().equals(result.resourceId())
        || !OPERATION.equals(result.operation())
        || job.version() != result.version()) {
      throw new IllegalArgumentException("Worker result does not match the job contract.");
    }
    if (job.status() == JobStatus.COMPLETED
        || job.status() == JobStatus.FAILED
        || job.status() == JobStatus.CANCELLED) {
      return;
    }
    String status = requiredString(result.data(), "status");
    if (!"READY".equals(status) && !"REJECTED".equals(status)) {
      throw new IllegalArgumentException("Unsupported worker result status.");
    }
    boolean ready = "READY".equals(status);
    Video video =
        videoRepository
            .findByIdForUser(result.resourceId(), job.userId())
            .orElseThrow(() -> new ResourceNotFoundException("Video for worker result not found."));
    Instant now = clock.instant();
    if (video.status() == VideoUploadStatus.VALIDATING) {
      Video validated =
          video.validated(
              ready ? VideoUploadStatus.READY : VideoUploadStatus.REJECTED,
              positiveLong(result.data().get("actualSizeBytes")),
              decimal(result.data().get("durationSeconds")),
              integer(result.data().get("width")),
              integer(result.data().get("height")),
              decimal(result.data().get("frameRate")),
              bool(result.data().get("hasAudio")),
              optionalString(result.data().get("failureCode")),
              now);
      videoRepository.updateValidation(validated, VideoUploadStatus.VALIDATING);
    }
    String output = serialize(result.data());
    stageRunRepository.complete(
        job.id(),
        STAGE,
        ready ? StageRunStatus.COMPLETED : StageRunStatus.FAILED,
        output,
        ready ? null : optionalString(result.data().get("failureCode")),
        ready ? null : "Video validation rejected the uploaded media.",
        now);
    jobRepository.complete(
        job.id(),
        ready ? JobStatus.COMPLETED : JobStatus.FAILED,
        STAGE,
        100,
        ready ? null : optionalString(result.data().get("failureCode")),
        ready ? null : "Video validation rejected the uploaded media.",
        now);
    pipelineRepository.updateStatus(
        job.pipelineId(), ready ? PipelineStatus.COMPLETED : PipelineStatus.FAILED, now);
  }

  private Video ownedVideo(UUID userId, UUID videoId) {
    return videoRepository
        .findByIdForUser(videoId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("Video not found."));
  }

  private String serialize(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new ProcessingException("Could not serialize a processing message.");
    }
  }

  public static String idempotencyKey(UUID videoId, int version) {
    return videoId + ":" + OPERATION + ":" + version;
  }

  private static String requiredString(Map<String, Object> data, String field) {
    String value = optionalString(data.get(field));
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required in a worker result");
    }
    return value;
  }

  private static StageRunStatus stageStatus(Map<String, Object> data) {
    String status = requiredString(data, "status");
    try {
      StageRunStatus value = StageRunStatus.valueOf(status);
      if (value == StageRunStatus.QUEUED || value == StageRunStatus.DEAD_LETTER) {
        throw new IllegalArgumentException("Unsupported worker stage status: " + status);
      }
      return value;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Unsupported worker stage status: " + status, exception);
    }
  }

  private static double progress(Object value) {
    if (value == null) {
      return 0;
    }
    if (!(value instanceof Number number)) {
      throw new IllegalArgumentException("progress must be numeric");
    }
    double progress = number.doubleValue();
    if (progress < 0 || progress > 100) {
      throw new IllegalArgumentException("progress must be between 0 and 100");
    }
    return progress;
  }

  private static String optionalString(Object value) {
    return value instanceof String string ? string : null;
  }

  private static Long positiveLong(Object value) {
    if (!(value instanceof Number number) || number.longValue() <= 0) {
      return null;
    }
    return number.longValue();
  }

  private static Integer integer(Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }

  private static BigDecimal decimal(Object value) {
    return value instanceof Number number ? BigDecimal.valueOf(number.doubleValue()) : null;
  }

  private static Boolean bool(Object value) {
    return value instanceof Boolean booleanValue ? booleanValue : null;
  }
}
