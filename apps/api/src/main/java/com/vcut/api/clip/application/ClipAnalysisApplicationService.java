package com.vcut.api.clip.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vcut.api.clip.domain.AnalysisRunStatus;
import com.vcut.api.clip.domain.CandidateAction;
import com.vcut.api.clip.domain.CandidateStatus;
import com.vcut.api.clip.domain.CandidateVariant;
import com.vcut.api.clip.domain.ClipAnalysisRun;
import com.vcut.api.clip.domain.ClipCandidate;
import com.vcut.api.clip.domain.DurationPreference;
import com.vcut.api.job.application.OutboxRepository;
import com.vcut.api.job.domain.OutboxMessage;
import com.vcut.api.shared.correlation.CorrelationContext;
import com.vcut.api.shared.errors.ConflictException;
import com.vcut.api.shared.errors.ProcessingException;
import com.vcut.api.shared.errors.ResourceNotFoundException;
import com.vcut.api.shared.errors.ValidationException;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClipAnalysisApplicationService {

  public static final String OPERATION = "CLIP_ANALYSIS";
  public static final String STAGE = "ANALYZE_CLIPS";
  public static final String COMMAND_EVENT_TYPE = "ClipAnalysisRequested";
  public static final String RESULT_EVENT_TYPE = "ClipAnalysisCompleted";
  public static final String STAGE_UPDATE_EVENT_TYPE = "StageRunUpdated";
  public static final String COMMAND_ROUTING_KEY = "pipeline.video.analyze-clips";

  private static final Set<String> RESULT_FIELDS =
      Set.of(
          "videoId",
          "pipelineVersion",
          "runId",
          "status",
          "candidates",
          "errorCode",
          "errorMessage",
          "failureCode",
          "provider",
          "durationSeconds",
          "hasReliableCandidate");
  private static final Set<String> STAGE_FIELDS =
      Set.of(
          "videoId",
          "pipelineVersion",
          "runId",
          "status",
          "progress",
          "attempt",
          "errorCode",
          "errorMessage",
          "timestamp");
  private static final Set<String> CANDIDATE_FIELDS =
      Set.of(
          "candidateId",
          "id",
          "analysisRunId",
          "videoId",
          "pipelineVersion",
          "variant",
          "status",
          "start",
          "end",
          "startSeconds",
          "endSeconds",
          "group",
          "groupKey",
          "groupId",
          "title",
          "description",
          "justification",
          "scores",
          "hookScore",
          "contextScore",
          "developmentScore",
          "payoffScore",
          "independenceScore",
          "engagementScore",
          "internalScore");
  private static final Set<String> SCORE_FIELDS =
      Set.of(
          "hook",
          "context",
          "development",
          "payoff",
          "independence",
          "engagement",
          "hookScore",
          "contextScore",
          "developmentScore",
          "payoffScore",
          "independenceScore",
          "engagementScore");

  private final VideoRepository videoRepository;
  private final TranscriptionRepository transcriptionRepository;
  private final ClipAnalysisRepository clipAnalysisRepository;
  private final OutboxRepository outboxRepository;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  @Autowired
  public ClipAnalysisApplicationService(
      VideoRepository videoRepository,
      TranscriptionRepository transcriptionRepository,
      ClipAnalysisRepository clipAnalysisRepository,
      OutboxRepository outboxRepository,
      ObjectMapper objectMapper) {
    this(
        videoRepository,
        transcriptionRepository,
        clipAnalysisRepository,
        outboxRepository,
        objectMapper,
        Clock.systemUTC());
  }

  ClipAnalysisApplicationService(
      VideoRepository videoRepository,
      TranscriptionRepository transcriptionRepository,
      ClipAnalysisRepository clipAnalysisRepository,
      OutboxRepository outboxRepository,
      ObjectMapper objectMapper,
      Clock clock) {
    this.videoRepository = videoRepository;
    this.transcriptionRepository = transcriptionRepository;
    this.clipAnalysisRepository = clipAnalysisRepository;
    this.outboxRepository = outboxRepository;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional
  public ClipAnalysisRun request(
      UUID userId, UUID videoId, DurationPreference durationPreference, BigDecimal customDuration) {
    Video video = ownedVideo(userId, videoId);
    if (video.status() != VideoUploadStatus.READY) {
      throw new ConflictException("Video must be ready before clip analysis.");
    }
    if (video.durationSeconds() == null) {
      throw new ConflictException("Video duration metadata is required before clip analysis.");
    }
    Transcription transcription =
        transcriptionRepository
            .findLatestForUser(videoId, userId)
            .filter(value -> value.status() == TranscriptionStatus.COMPLETED)
            .orElseThrow(
                () -> new ConflictException("A completed latest transcription is required."));
    DurationPreference preference =
        durationPreference == null ? DurationPreference.AUTO : durationPreference;
    validatePreference(preference, customDuration);
    BigDecimal durationSeconds =
        preference == DurationPreference.CUSTOM
            ? customDuration
            : preference.defaultDurationSeconds();
    Instant now = clock.instant();
    int version = clipAnalysisRepository.nextVersion(videoId);
    ClipAnalysisRun run =
        ClipAnalysisRun.queued(
            UUID.randomUUID(),
            videoId,
            userId,
            version,
            preference,
            customDuration,
            durationSeconds,
            transcription.language(),
            now);
    Map<String, Object> commandData = new LinkedHashMap<>();
    commandData.put("videoId", videoId);
    commandData.put("pipelineVersion", version);
    commandData.put("durationSeconds", video.durationSeconds());
    commandData.put("language", transcription.language());
    commandData.put("text", transcription.text());
    commandData.put("segments", segments(transcription.segments()));
    commandData.put("durationPreference", preference.name());
    commandData.put("customDurationSeconds", customDuration);
    MessageEnvelope command =
        new MessageEnvelope(
            MessageKind.COMMAND,
            UUID.randomUUID(),
            COMMAND_EVENT_TYPE,
            1,
            run.id(),
            videoId,
            OPERATION,
            version,
            CorrelationContext.current().orElseGet(UUID::randomUUID),
            1,
            now,
            commandData);
    ClipAnalysisRun savedRun = clipAnalysisRepository.saveRun(run);
    outboxRepository.save(
        new OutboxMessage(
            UUID.randomUUID(),
            OPERATION,
            run.id(),
            COMMAND_EVENT_TYPE,
            COMMAND_ROUTING_KEY,
            serialize(command),
            0,
            now,
            null,
            null,
            now));
    return savedRun == null ? run : savedRun;
  }

  @Transactional(readOnly = true)
  public List<ClipCandidate> list(UUID userId, UUID videoId) {
    ownedVideo(userId, videoId);
    return clipAnalysisRepository.findLatestCandidatesForUser(videoId, userId);
  }

  @Transactional(readOnly = true)
  public ClipAnalysisRun latestRun(UUID userId, UUID videoId) {
    ownedVideo(userId, videoId);
    return clipAnalysisRepository
        .findLatestRunForUser(videoId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("Clip analysis run not found."));
  }

  @Transactional(readOnly = true)
  public ClipCandidate get(UUID userId, UUID videoId, UUID candidateId) {
    ownedVideo(userId, videoId);
    ClipCandidate candidate =
        clipAnalysisRepository
            .findCandidateForUser(candidateId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Clip candidate not found."));
    if (!candidate.videoId().equals(videoId)) {
      throw new ResourceNotFoundException("Clip candidate not found.");
    }
    ClipAnalysisRun latestRun =
        clipAnalysisRepository
            .findLatestRunForUser(videoId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Clip candidate not found."));
    if (!candidate.analysisRunId().equals(latestRun.id())) {
      throw new ResourceNotFoundException("Clip candidate not found.");
    }
    return candidate;
  }

  @Transactional
  public ClipCandidate accept(UUID userId, UUID candidateId) {
    return applyAction(userId, candidateId, CandidateAction.ACCEPT);
  }

  @Transactional
  public ClipCandidate discard(UUID userId, UUID candidateId) {
    return applyAction(userId, candidateId, CandidateAction.DISCARD);
  }

  @Transactional
  public ClipCandidate select(UUID userId, UUID candidateId) {
    return applyAction(userId, candidateId, CandidateAction.SELECT);
  }

  @Transactional
  public void handleStageUpdate(MessageEnvelope update) {
    validateEnvelope(update, STAGE_UPDATE_EVENT_TYPE);
    validateKeys(update.data(), STAGE_FIELDS, "stage update");
    ClipAnalysisRun run = matchingRun(update);
    String status = requiredString(update.data(), "status");
    AnalysisRunStatus analysisStatus = stageStatus(status);
    if (run.status() == AnalysisRunStatus.COMPLETED || run.status() == AnalysisRunStatus.FAILED) {
      return;
    }
    if (analysisStatus == AnalysisRunStatus.COMPLETED) {
      analysisStatus = AnalysisRunStatus.PROCESSING;
    }
    clipAnalysisRepository.updateRunStatus(
        run.id(),
        analysisStatus,
        optionalString(update.data().get("errorCode")),
        optionalString(update.data().get("errorMessage")),
        update.occurredAt());
  }

  @Transactional
  public void handleResult(MessageEnvelope result) {
    validateEnvelope(result, RESULT_EVENT_TYPE);
    validateKeys(result.data(), RESULT_FIELDS, "analysis result");
    ClipAnalysisRun run = matchingRun(result);
    String status =
        result.data().containsKey("status") ? requiredString(result.data(), "status") : "COMPLETED";
    if (!"COMPLETED".equals(status) && !"FAILED".equals(status)) {
      throw new IllegalArgumentException("Unsupported clip analysis result status: " + status);
    }
    if (run.status() == AnalysisRunStatus.COMPLETED || run.status() == AnalysisRunStatus.FAILED) {
      return;
    }
    List<ClipCandidate> candidates =
        parseCandidates(result.data().get("candidates"), run, result.occurredAt());
    validateResultMetadata(result.data(), candidates);
    if ("FAILED".equals(status) && !candidates.isEmpty()) {
      throw new IllegalArgumentException(
          "Failed clip analysis results must not contain candidates");
    }
    if ("COMPLETED".equals(status)) {
      clipAnalysisRepository.saveCandidates(candidates);
    }
    clipAnalysisRepository.updateRunStatus(
        run.id(),
        "COMPLETED".equals(status) ? AnalysisRunStatus.COMPLETED : AnalysisRunStatus.FAILED,
        optionalString(result.data().get("errorCode")),
        firstString(result.data().get("errorMessage"), result.data().get("failureCode")),
        result.occurredAt());
  }

  private ClipCandidate applyAction(UUID userId, UUID candidateId, CandidateAction action) {
    ClipCandidate candidate =
        clipAnalysisRepository
            .findCandidateForUser(candidateId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Clip candidate not found."));
    Instant now = clock.instant();
    CandidateStatus status =
        switch (action) {
          case ACCEPT -> CandidateStatus.ACCEPTED;
          case DISCARD -> CandidateStatus.DISCARDED;
          case SELECT -> CandidateStatus.SELECTED;
        };
    clipAnalysisRepository.recordAction(candidate.id(), candidate.videoId(), userId, action, now);
    clipAnalysisRepository.updateCandidateStatus(candidate.id(), userId, status, now);
    return candidate.withStatus(status, now);
  }

  private ClipAnalysisRun matchingRun(MessageEnvelope envelope) {
    UUID resourceId = envelope.resourceId();
    if (envelope.eventVersion() != 1 || envelope.version() < 1) {
      throw new IllegalArgumentException("Unsupported clip analysis message version");
    }
    ClipAnalysisRun run =
        clipAnalysisRepository
            .findRunById(envelope.jobId())
            .orElseGet(
                () -> {
                  Integer pipelineVersion = optionalInteger(envelope.data().get("pipelineVersion"));
                  return clipAnalysisRepository
                      .findRun(
                          resourceId,
                          pipelineVersion == null ? envelope.version() : pipelineVersion)
                      .orElseThrow(
                          () -> new ResourceNotFoundException("Clip analysis run not found."));
                });
    if (!run.id().equals(envelope.jobId())
        || !run.videoId().equals(resourceId)
        || run.pipelineVersion() < 1) {
      throw new IllegalArgumentException("Worker clip analysis event does not match the run");
    }
    validateOptionalIdentity(envelope.data(), "videoId", resourceId);
    validateOptionalIdentity(envelope.data(), "runId", run.id());
    Integer pipelineVersion = optionalInteger(envelope.data().get("pipelineVersion"));
    if (pipelineVersion != null && pipelineVersion != run.pipelineVersion()) {
      throw new IllegalArgumentException("Worker clip analysis event has an invalid version");
    }
    return run;
  }

  private List<ClipCandidate> parseCandidates(
      Object rawCandidates, ClipAnalysisRun run, Instant occurredAt) {
    if (rawCandidates == null) {
      return List.of();
    }
    if (!(rawCandidates instanceof List<?> values)) {
      throw new IllegalArgumentException("candidates must be an array");
    }
    if (values.size() > 20) {
      throw new IllegalArgumentException("candidates must contain at most 20 entries");
    }
    Video video =
        videoRepository
            .findByIdForUser(run.videoId(), run.userId())
            .orElseThrow(() -> new ResourceNotFoundException("Video for clip analysis not found."));
    Set<UUID> ids = new HashSet<>();
    return values.stream()
        .map(value -> parseCandidate(value, run, video, occurredAt, ids))
        .toList();
  }

  private ClipCandidate parseCandidate(
      Object rawCandidate,
      ClipAnalysisRun run,
      Video video,
      Instant occurredAt,
      Set<UUID> candidateIds) {
    Map<String, Object> data = mapValue(rawCandidate, "candidate");
    validateKeys(data, CANDIDATE_FIELDS, "candidate");
    UUID id = requiredUuid(data, data.containsKey("candidateId") ? "candidateId" : "id");
    if (!candidateIds.add(id)) {
      throw new IllegalArgumentException("candidate ids must be unique within a result");
    }
    UUID analysisRunId =
        data.containsKey("analysisRunId") ? requiredUuid(data, "analysisRunId") : run.id();
    if (!analysisRunId.equals(run.id())) {
      throw new IllegalArgumentException("candidate analysisRunId does not match the run");
    }
    Integer candidateVersion = optionalInteger(data.get("pipelineVersion"));
    if (candidateVersion != null && candidateVersion != run.pipelineVersion()) {
      throw new IllegalArgumentException("candidate pipelineVersion does not match the run");
    }
    if (data.containsKey("videoId")) {
      validateOptionalIdentity(data, "videoId", run.videoId());
    }
    CandidateVariant variant = enumValue(data, "variant", CandidateVariant.class);
    CandidateStatus status =
        data.containsKey("status")
            ? enumValue(data, "status", CandidateStatus.class)
            : CandidateStatus.SUGGESTED;
    BigDecimal start = decimal(data, data.containsKey("startSeconds") ? "startSeconds" : "start");
    BigDecimal end = decimal(data, data.containsKey("endSeconds") ? "endSeconds" : "end");
    if (video.durationSeconds() != null && end.compareTo(video.durationSeconds()) > 0) {
      throw new IllegalArgumentException("candidate interval is outside the video");
    }
    Map<String, Object> scores =
        data.containsKey("scores") ? mapValue(data.get("scores"), "scores") : Map.of();
    validateKeys(scores, SCORE_FIELDS, "scores");
    String group =
        data.containsKey("groupId")
            ? requiredUuid(data, "groupId").toString()
            : requiredText(data, data.containsKey("groupKey") ? "groupKey" : "group");
    return new ClipCandidate(
        id,
        run.id(),
        run.videoId(),
        run.userId(),
        variant,
        status,
        start,
        end,
        group,
        requiredText(data, "title"),
        requiredText(data, "description"),
        requiredText(data, "justification"),
        score(data, scores, "hook"),
        score(data, scores, "context"),
        score(data, scores, "development"),
        score(data, scores, "payoff"),
        score(data, scores, "independence"),
        score(data, scores, "engagement"),
        occurredAt,
        occurredAt);
  }

  private static void validateResultMetadata(
      Map<String, Object> data, List<ClipCandidate> candidates) {
    if (data.containsKey("provider")) {
      requiredText(data, "provider");
    }
    if (data.containsKey("durationSeconds")) {
      BigDecimal duration = decimal(data, "durationSeconds");
      if (duration.signum() < 0) {
        throw new IllegalArgumentException("durationSeconds must not be negative");
      }
    }
    if (data.containsKey("hasReliableCandidate")) {
      Object value = data.get("hasReliableCandidate");
      if (!(value instanceof Boolean reliable) || reliable != !candidates.isEmpty()) {
        throw new IllegalArgumentException("hasReliableCandidate does not match candidates");
      }
    }
  }

  private static void validatePreference(
      DurationPreference preference, BigDecimal customDurationSeconds) {
    if (preference == DurationPreference.CUSTOM && customDurationSeconds == null) {
      throw new ValidationException("customDurationSeconds is required for CUSTOM preference.");
    }
    if (preference != DurationPreference.CUSTOM && customDurationSeconds != null) {
      throw new ValidationException(
          "customDurationSeconds is only valid for CUSTOM duration preference.");
    }
    if (customDurationSeconds != null
        && (customDurationSeconds.signum() <= 0
            || customDurationSeconds.compareTo(BigDecimal.valueOf(90)) > 0)) {
      throw new ValidationException("customDurationSeconds must be greater than 0 and at most 90.");
    }
  }

  private static BigDecimal score(
      Map<String, Object> candidate, Map<String, Object> scores, String shortName) {
    String longName = shortName + "Score";
    Object value =
        scores.containsKey(shortName)
            ? scores.get(shortName)
            : scores.containsKey(longName) ? scores.get(longName) : candidate.get(longName);
    return decimal(value, longName);
  }

  private static List<Map<String, Object>> segments(List<TranscriptionSegment> source) {
    return source.stream().map(ClipAnalysisApplicationService::segment).toList();
  }

  private static Map<String, Object> segment(TranscriptionSegment value) {
    Map<String, Object> segment = new LinkedHashMap<>();
    segment.put("text", value.text());
    segment.put("startSeconds", value.startSeconds());
    segment.put("endSeconds", value.endSeconds());
    segment.put("confidence", value.confidence());
    return segment;
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
      throw new ProcessingException("Could not serialize a clip analysis message.");
    }
  }

  private static void validateEnvelope(MessageEnvelope envelope, String eventType) {
    if (envelope.kind() != MessageKind.EVENT
        || envelope.eventVersion() != 1
        || !eventType.equals(envelope.eventType())
        || !OPERATION.equals(envelope.operation())) {
      throw new IllegalArgumentException("Unsupported clip analysis worker event.");
    }
  }

  private static AnalysisRunStatus stageStatus(String value) {
    try {
      AnalysisRunStatus status = AnalysisRunStatus.valueOf(value);
      if (status == AnalysisRunStatus.QUEUED) {
        throw new IllegalArgumentException("QUEUED is not a stage update status");
      }
      return status;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Unsupported clip analysis stage status: " + value);
    }
  }

  private static void validateKeys(
      Map<String, Object> data, Set<String> allowed, String valueName) {
    for (String key : data.keySet()) {
      if (!allowed.contains(key)) {
        throw new IllegalArgumentException("Unknown " + valueName + " field: " + key);
      }
    }
  }

  private static Map<String, Object> mapValue(Object value, String field) {
    if (!(value instanceof Map<?, ?> raw)) {
      throw new IllegalArgumentException(field + " must be an object");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : raw.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException(field + " contains a non-text field name");
      }
      result.put(key, entry.getValue());
    }
    return result;
  }

  private static String requiredString(Map<String, Object> data, String field) {
    return requiredText(data, field);
  }

  private static String requiredText(Map<String, Object> data, String field) {
    String value = optionalString(data.get(field));
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private static String optionalString(Object value) {
    return value instanceof String string ? string : null;
  }

  private static String firstString(Object first, Object second) {
    String firstValue = optionalString(first);
    return firstValue == null ? optionalString(second) : firstValue;
  }

  private static UUID requiredUuid(Map<String, Object> data, String field) {
    Object value = data.get(field);
    if (value instanceof UUID uuid) {
      return uuid;
    }
    if (value instanceof String string) {
      try {
        return UUID.fromString(string);
      } catch (IllegalArgumentException exception) {
        throw new IllegalArgumentException(field + " must be a UUID", exception);
      }
    }
    throw new IllegalArgumentException(field + " must be a UUID");
  }

  private static void validateOptionalIdentity(
      Map<String, Object> data, String field, UUID expected) {
    if (data.containsKey(field) && !expected.equals(requiredUuid(data, field))) {
      throw new IllegalArgumentException(field + " does not match the event resource");
    }
  }

  private static Integer optionalInteger(Object value) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof Number number)) {
      throw new IllegalArgumentException("pipelineVersion must be numeric");
    }
    double numeric = number.doubleValue();
    if (!Double.isFinite(numeric) || numeric != Math.rint(numeric)) {
      throw new IllegalArgumentException("pipelineVersion must be a finite integer");
    }
    return number.intValue();
  }

  private static BigDecimal decimal(Map<String, Object> data, String field) {
    return decimal(data.get(field), field);
  }

  private static BigDecimal decimal(Object value, String field) {
    if (!(value instanceof Number number)) {
      throw new IllegalArgumentException(field + " must be numeric");
    }
    if (number instanceof Double || number instanceof Float) {
      double numeric = number.doubleValue();
      if (!Double.isFinite(numeric)) {
        throw new IllegalArgumentException(field + " must be finite");
      }
      return BigDecimal.valueOf(numeric);
    }
    return new BigDecimal(number.toString());
  }

  private static <T extends Enum<T>> T enumValue(
      Map<String, Object> data, String field, Class<T> enumType) {
    String value = requiredText(data, field);
    try {
      return Enum.valueOf(enumType, value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Unsupported " + field + ": " + value, exception);
    }
  }
}
