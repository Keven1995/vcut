package com.vcut.api.transcription.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vcut.api.job.application.OutboxRepository;
import com.vcut.api.job.domain.OutboxMessage;
import com.vcut.api.shared.correlation.CorrelationContext;
import com.vcut.api.shared.errors.ConflictException;
import com.vcut.api.shared.errors.ProcessingException;
import com.vcut.api.shared.errors.ResourceNotFoundException;
import com.vcut.api.shared.messaging.MessageEnvelope;
import com.vcut.api.shared.messaging.MessageKind;
import com.vcut.api.transcription.domain.Transcription;
import com.vcut.api.transcription.domain.TranscriptionSegment;
import com.vcut.api.transcription.domain.TranscriptionStatus;
import com.vcut.api.transcription.domain.TranscriptionWord;
import com.vcut.api.usage.application.RetentionApplicationService;
import com.vcut.api.usage.domain.RetentionAssetType;
import com.vcut.api.video.application.ObjectStorage;
import com.vcut.api.video.application.VideoRepository;
import com.vcut.api.video.domain.Video;
import com.vcut.api.video.domain.VideoUploadStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnBean(ObjectStorage.class)
public class TranscriptionApplicationService {

  public static final String OPERATION = "TRANSCRIPTION";
  public static final String STAGE = "TRANSCRIBE";
  public static final String COMMAND_EVENT_TYPE = "TranscriptionRequested";
  public static final String RESULT_EVENT_TYPE = "TranscriptionCompleted";
  public static final String STAGE_UPDATE_EVENT_TYPE = "StageRunUpdated";
  public static final String COMMAND_ROUTING_KEY = "pipeline.video.transcribe";

  private final VideoRepository videoRepository;
  private final com.vcut.api.video.application.ObjectStorage objectStorage;
  private final TranscriptionRepository transcriptionRepository;
  private final OutboxRepository outboxRepository;
  private final ObjectMapper objectMapper;
  private final com.vcut.api.usage.application.UsageApplicationService usageApplicationService;
  private final RetentionApplicationService retentionApplicationService;
  private final Clock clock;

  @Autowired
  public TranscriptionApplicationService(
      VideoRepository videoRepository,
      com.vcut.api.video.application.ObjectStorage objectStorage,
      TranscriptionRepository transcriptionRepository,
      OutboxRepository outboxRepository,
      ObjectMapper objectMapper,
      com.vcut.api.usage.application.UsageApplicationService usageApplicationService,
      RetentionApplicationService retentionApplicationService) {
    this(
        videoRepository,
        objectStorage,
        transcriptionRepository,
        outboxRepository,
        objectMapper,
        usageApplicationService,
        retentionApplicationService,
        Clock.systemUTC());
  }

  public TranscriptionApplicationService(
      VideoRepository videoRepository,
      com.vcut.api.video.application.ObjectStorage objectStorage,
      TranscriptionRepository transcriptionRepository,
      OutboxRepository outboxRepository,
      ObjectMapper objectMapper) {
    this(
        videoRepository,
        objectStorage,
        transcriptionRepository,
        outboxRepository,
        objectMapper,
        null,
        null,
        Clock.systemUTC());
  }

  TranscriptionApplicationService(
      VideoRepository videoRepository,
      com.vcut.api.video.application.ObjectStorage objectStorage,
      TranscriptionRepository transcriptionRepository,
      OutboxRepository outboxRepository,
      ObjectMapper objectMapper,
      Clock clock) {
    this(
        videoRepository,
        objectStorage,
        transcriptionRepository,
        outboxRepository,
        objectMapper,
        null,
        null,
        clock);
  }

  TranscriptionApplicationService(
      VideoRepository videoRepository,
      com.vcut.api.video.application.ObjectStorage objectStorage,
      TranscriptionRepository transcriptionRepository,
      OutboxRepository outboxRepository,
      ObjectMapper objectMapper,
      com.vcut.api.usage.application.UsageApplicationService usageApplicationService,
      Clock clock) {
    this(
        videoRepository,
        objectStorage,
        transcriptionRepository,
        outboxRepository,
        objectMapper,
        usageApplicationService,
        null,
        clock);
  }

  TranscriptionApplicationService(
      VideoRepository videoRepository,
      com.vcut.api.video.application.ObjectStorage objectStorage,
      TranscriptionRepository transcriptionRepository,
      OutboxRepository outboxRepository,
      ObjectMapper objectMapper,
      com.vcut.api.usage.application.UsageApplicationService usageApplicationService,
      RetentionApplicationService retentionApplicationService,
      Clock clock) {
    this.videoRepository = videoRepository;
    this.objectStorage = objectStorage;
    this.transcriptionRepository = transcriptionRepository;
    this.outboxRepository = outboxRepository;
    this.objectMapper = objectMapper;
    this.usageApplicationService = usageApplicationService;
    this.retentionApplicationService = retentionApplicationService;
    this.clock = clock;
  }

  @Transactional
  public Transcription request(UUID userId, UUID videoId, String requestedLanguage) {
    Video video = ownedVideo(userId, videoId);
    if (video.status() != VideoUploadStatus.READY) {
      throw new ConflictException("Video must be ready before transcription.");
    }
    String language = normalizeLanguage(requestedLanguage);
    int version = transcriptionRepository.nextVersion(videoId);
    Instant now = clock.instant();
    if (usageApplicationService != null) {
      usageApplicationService.reserveProcessingMinutes(
          userId, videoId, "TRANSCRIPTION", version, video.durationSeconds());
    }
    if (retentionApplicationService != null) {
      String audioKey = audioObjectKey(video, version);
      objectStorage
          .head(audioKey)
          .ifPresent(
              stored ->
                  retentionApplicationService.register(
                      userId,
                      video.projectId(),
                      audioKey,
                      RetentionAssetType.AUDIO,
                      stored.contentLength()));
    }
    Transcription transcription =
        Transcription.queued(
            UUID.randomUUID(), videoId, userId, version, "configured", language, now);
    MessageEnvelope command =
        new MessageEnvelope(
            MessageKind.COMMAND,
            UUID.randomUUID(),
            COMMAND_EVENT_TYPE,
            1,
            transcription.id(),
            videoId,
            OPERATION,
            version,
            CorrelationContext.current().orElseGet(UUID::randomUUID),
            1,
            now,
            Map.of(
                "videoId", videoId,
                "pipelineVersion", version,
                "audioObjectKey", audioObjectKey(video, version),
                "language", language));
    String payload = serialize(command);
    transcriptionRepository.save(transcription);
    outboxRepository.save(
        new OutboxMessage(
            UUID.randomUUID(),
            "TRANSCRIPTION",
            transcription.id(),
            COMMAND_EVENT_TYPE,
            COMMAND_ROUTING_KEY,
            payload,
            0,
            now,
            null,
            null,
            now));
    return transcription;
  }

  @Transactional(readOnly = true)
  public Transcription get(UUID userId, UUID videoId) {
    ownedVideo(userId, videoId);
    return transcriptionRepository
        .findLatestForUser(videoId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("Transcription not found."));
  }

  @Transactional(readOnly = true)
  public com.vcut.api.video.application.ObjectStorage.PresignedDownload audioUrl(
      UUID userId, UUID videoId) {
    Video video = ownedVideo(userId, videoId);
    Transcription transcription = get(userId, videoId);
    return objectStorage.presignDownload(audioObjectKey(video, transcription.pipelineVersion()));
  }

  @Transactional
  public void handleStageUpdate(MessageEnvelope update) {
    validateEnvelope(update, STAGE_UPDATE_EVENT_TYPE);
    String status = requiredString(update.data(), "status");
    TranscriptionStatusValue stageStatus = TranscriptionStatusValue.from(status);
    transcriptionRepository.updateStatus(
        update.resourceId(),
        update.version(),
        stageStatus.status,
        optionalString(update.data().get("errorCode")),
        optionalString(update.data().get("errorMessage")),
        update.occurredAt());
    if (stageStatus == TranscriptionStatusValue.FAILED && usageApplicationService != null) {
      transcriptionRepository
          .findByVideoAndVersion(update.resourceId(), update.version())
          .ifPresent(
              transcription ->
                  usageApplicationService.releaseProcessingReservation(
                      transcription.userId(),
                      update.resourceId(),
                      "TRANSCRIPTION",
                      update.version()));
    }
  }

  @Transactional
  public void handleResult(MessageEnvelope result) {
    validateEnvelope(result, RESULT_EVENT_TYPE);
    WorkerTranscriptionPayload payload =
        objectMapper.convertValue(result.data(), WorkerTranscriptionPayload.class);
    if (!payload.videoId().equals(result.resourceId())
        || payload.pipelineVersion() != result.version()) {
      throw new IllegalArgumentException("Worker transcription result does not match the request.");
    }
    Transcription queued =
        transcriptionRepository
            .findByVideoAndVersion(result.resourceId(), result.version())
            .orElseThrow(() -> new ResourceNotFoundException("Transcription request not found."));
    Transcription completed =
        new Transcription(
            queued.id(),
            queued.videoId(),
            queued.userId(),
            queued.pipelineVersion(),
            payload.provider(),
            payload.language(),
            payload.text(),
            payload.durationSeconds(),
            payload.confidence(),
            TranscriptionStatus.COMPLETED,
            payload.segments().stream().map(TranscriptionApplicationService::segment).toList(),
            null,
            null,
            queued.createdAt(),
            result.occurredAt(),
            result.occurredAt());
    transcriptionRepository.complete(completed);
    if (usageApplicationService != null) {
      usageApplicationService.confirmProcessing(
          queued.userId(),
          result.resourceId(),
          "TRANSCRIPTION",
          result.version(),
          payload.durationSeconds());
    }
  }

  public String audioObjectKey(UUID userId, UUID projectId, UUID videoId, int version) {
    return "users/%s/projects/%s/audio/%s/v%d/transcription.wav"
        .formatted(userId, projectId, videoId, version);
  }

  private static String audioObjectKey(Video video, int version) {
    return "users/%s/projects/%s/audio/%s/v%d/transcription.wav"
        .formatted(video.userId(), video.projectId(), video.id(), version);
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
      throw new ProcessingException("Could not serialize a transcription message.");
    }
  }

  private static String normalizeLanguage(String language) {
    if (language == null || language.isBlank()) {
      return "und";
    }
    String normalized = language.trim();
    if (!normalized.matches("[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8})?")) {
      throw new IllegalArgumentException("language must be a valid language code");
    }
    return normalized;
  }

  private static void validateEnvelope(MessageEnvelope envelope, String eventType) {
    if (!eventType.equals(envelope.eventType())
        || envelope.kind() != MessageKind.EVENT
        || !OPERATION.equals(envelope.operation())) {
      throw new IllegalArgumentException("Unsupported transcription worker event.");
    }
  }

  private static String requiredString(Map<String, Object> data, String field) {
    String value = optionalString(data.get(field));
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required in a worker event");
    }
    return value;
  }

  private static String optionalString(Object value) {
    return value instanceof String string ? string : null;
  }

  private enum TranscriptionStatusValue {
    PROCESSING(com.vcut.api.transcription.domain.TranscriptionStatus.PROCESSING),
    RETRYING(com.vcut.api.transcription.domain.TranscriptionStatus.RETRYING),
    COMPLETED(com.vcut.api.transcription.domain.TranscriptionStatus.COMPLETED),
    FAILED(com.vcut.api.transcription.domain.TranscriptionStatus.FAILED);

    private final com.vcut.api.transcription.domain.TranscriptionStatus status;

    TranscriptionStatusValue(com.vcut.api.transcription.domain.TranscriptionStatus status) {
      this.status = status;
    }

    private static TranscriptionStatusValue from(String value) {
      try {
        return valueOf(value);
      } catch (IllegalArgumentException exception) {
        throw new IllegalArgumentException("Unsupported transcription stage status: " + value);
      }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record WorkerTranscriptionPayload(
      UUID videoId,
      int pipelineVersion,
      String provider,
      String language,
      String text,
      java.math.BigDecimal durationSeconds,
      java.math.BigDecimal confidence,
      List<WorkerSegmentPayload> segments) {
    private WorkerTranscriptionPayload {
      segments = segments == null ? List.of() : List.copyOf(segments);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record WorkerSegmentPayload(
      String text,
      @JsonProperty("start") java.math.BigDecimal startSeconds,
      @JsonProperty("end") java.math.BigDecimal endSeconds,
      java.math.BigDecimal confidence,
      List<WorkerWordPayload> words) {
    private WorkerSegmentPayload {
      words = words == null ? List.of() : List.copyOf(words);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record WorkerWordPayload(
      String text,
      @JsonProperty("start") java.math.BigDecimal startSeconds,
      @JsonProperty("end") java.math.BigDecimal endSeconds,
      java.math.BigDecimal confidence) {}

  private static TranscriptionSegment segment(WorkerSegmentPayload value) {
    return new TranscriptionSegment(
        value.text(),
        value.startSeconds(),
        value.endSeconds(),
        value.confidence(),
        value.words().stream()
            .map(
                word ->
                    new TranscriptionWord(
                        word.text(), word.startSeconds(), word.endSeconds(), word.confidence()))
            .toList());
  }
}
