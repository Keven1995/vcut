package com.vcut.api.clip.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vcut.api.clip.domain.AspectRatio;
import com.vcut.api.clip.domain.CaptionAnimation;
import com.vcut.api.clip.domain.CaptionCue;
import com.vcut.api.clip.domain.CaptionPosition;
import com.vcut.api.clip.domain.CaptionPreset;
import com.vcut.api.clip.domain.CaptionStyle;
import com.vcut.api.clip.domain.Clip;
import com.vcut.api.clip.domain.ClipCandidate;
import com.vcut.api.clip.domain.ClipScore;
import com.vcut.api.clip.domain.ClipStatus;
import com.vcut.api.clip.domain.ClipVersion;
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
import com.vcut.api.transcription.domain.TranscriptionWord;
import com.vcut.api.video.application.ObjectStorage;
import com.vcut.api.video.application.VideoRepository;
import com.vcut.api.video.domain.Video;
import com.vcut.api.video.domain.VideoUploadStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClipApplicationService {

  public static final String OPERATION = "CLIP_GENERATION";
  public static final String COMMAND_EVENT_TYPE = "ClipGenerationRequested";
  public static final String RESULT_EVENT_TYPE = "ClipGenerationCompleted";
  public static final String STAGE_UPDATE_EVENT_TYPE = "StageRunUpdated";
  public static final String COMMAND_ROUTING_KEY = "pipeline.video.generate-clip";

  private static final Set<String> RESULT_FIELDS =
      Set.of(
          "clipId",
          "editVersion",
          "status",
          "outputObjectKey",
          "durationSeconds",
          "width",
          "height",
          "aspectRatio",
          "errorCode",
          "errorMessage");
  private static final Set<String> STAGE_FIELDS =
      Set.of("status", "progress", "attempt", "errorCode", "errorMessage");

  private final VideoRepository videoRepository;
  private final ClipAnalysisRepository clipAnalysisRepository;
  private final TranscriptionRepository transcriptionRepository;
  private final ClipRepository clipRepository;
  private final OutboxRepository outboxRepository;
  private final ObjectStorage objectStorage;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  @Autowired
  public ClipApplicationService(
      VideoRepository videoRepository,
      ClipAnalysisRepository clipAnalysisRepository,
      TranscriptionRepository transcriptionRepository,
      ClipRepository clipRepository,
      OutboxRepository outboxRepository,
      @Nullable ObjectStorage objectStorage,
      ObjectMapper objectMapper) {
    this(
        videoRepository,
        clipAnalysisRepository,
        transcriptionRepository,
        clipRepository,
        outboxRepository,
        objectStorage,
        objectMapper,
        Clock.systemUTC());
  }

  ClipApplicationService(
      VideoRepository videoRepository,
      ClipAnalysisRepository clipAnalysisRepository,
      TranscriptionRepository transcriptionRepository,
      ClipRepository clipRepository,
      OutboxRepository outboxRepository,
      ObjectStorage objectStorage,
      ObjectMapper objectMapper,
      Clock clock) {
    this.videoRepository = videoRepository;
    this.clipAnalysisRepository = clipAnalysisRepository;
    this.transcriptionRepository = transcriptionRepository;
    this.clipRepository = clipRepository;
    this.outboxRepository = outboxRepository;
    this.objectStorage = objectStorage;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional
  public ClipAggregate create(
      UUID userId,
      UUID videoId,
      UUID candidateId,
      String aspectRatioValue,
      String captionPresetValue) {
    Video video = ownedReadyVideo(userId, videoId);
    ClipCandidate candidate = ownedCandidate(userId, videoId, candidateId);
    validateAgainstVideo(candidate.startSeconds(), candidate.endSeconds(), video);
    AspectRatio aspectRatio = parseAspectRatio(aspectRatioValue);
    CaptionPreset captionPreset = parseCaptionPreset(captionPresetValue);
    Instant now = clock.instant();
    Clip clip =
        Clip.created(
            UUID.randomUUID(),
            userId,
            video.projectId(),
            video.id(),
            candidate.id(),
            ClipScore.from(candidate),
            now);
    Transcription transcription = completedTranscription(video.id(), userId);
    ClipVersion version =
        ClipVersion.initial(
            UUID.randomUUID(),
            clip,
            candidate.startSeconds(),
            candidate.endSeconds(),
            aspectRatio,
            captionPreset,
            captionPreset.defaultStyle(),
            cuesFor(transcription, candidate.startSeconds(), candidate.endSeconds()),
            now);
    clipRepository.save(clip);
    clipRepository.saveVersion(version);
    return new ClipAggregate(clip, version);
  }

  @Transactional(readOnly = true)
  public ClipPage list(UUID userId, UUID videoId, int page, int size, String statusValue) {
    ownedReadyVideo(userId, videoId);
    validatePage(page, size);
    ClipStatus status =
        statusValue == null || statusValue.isBlank() ? null : parseStatus(statusValue);
    return clipRepository.findByVideoForUser(videoId, userId, page, size, status);
  }

  @Transactional(readOnly = true)
  public ClipAggregate get(UUID userId, UUID clipId) {
    return clipRepository
        .findByIdForUser(clipId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("Clip not found."));
  }

  @Transactional
  public ClipAggregate update(UUID userId, UUID clipId, ClipEditCommand command) {
    ClipAggregate aggregate = get(userId, clipId);
    Video video = ownedReadyVideo(userId, aggregate.clip().videoId());
    ClipVersion current = aggregate.version();
    BigDecimal start =
        command.startSeconds() == null ? current.startSeconds() : command.startSeconds();
    BigDecimal end = command.endSeconds() == null ? current.endSeconds() : command.endSeconds();
    validateAgainstVideo(start, end, video);

    AspectRatio aspectRatio =
        command.aspectRatio() == null
            ? current.aspectRatio()
            : parseAspectRatio(command.aspectRatio());
    CaptionPreset captionPreset =
        command.captionPreset() == null
            ? current.captionPreset()
            : parseCaptionPreset(command.captionPreset());
    CaptionStyle style = validatedStyle(current, command, captionPreset);
    boolean intervalChanged =
        start.compareTo(current.startSeconds()) != 0 || end.compareTo(current.endSeconds()) != 0;
    List<CaptionCue> cues =
        command.captionText() != null
            ? manualCue(command.captionText(), end.subtract(start))
            : intervalChanged
                ? cuesFor(completedTranscription(video.id(), userId), start, end)
                : current.captionCues();
    Instant now = clock.instant();
    ClipVersion next =
        current.next(UUID.randomUUID(), start, end, aspectRatio, captionPreset, style, cues, now);
    Clip edited = aggregate.clip().edited(next.editVersion(), now);
    clipRepository.saveVersion(next);
    clipRepository.update(edited);
    return new ClipAggregate(edited, next);
  }

  @Transactional
  public ClipAggregate generate(UUID userId, UUID clipId) {
    ClipAggregate aggregate = get(userId, clipId);
    Clip clip = aggregate.clip();
    ClipVersion version = aggregate.version();
    if (clip.status() == ClipStatus.READY
        && clip.outputEditVersion() != null
        && clip.outputEditVersion() == version.editVersion()) {
      return aggregate;
    }
    Instant now = clock.instant();
    Clip queued = clip.queued(now);
    if (!clipRepository.claimGeneration(clip.id(), userId, version.editVersion(), now)) {
      return get(userId, clipId);
    }
    Map<String, Object> commandData = generationData(queued, version);
    MessageEnvelope command =
        new MessageEnvelope(
            MessageKind.COMMAND,
            UUID.randomUUID(),
            COMMAND_EVENT_TYPE,
            1,
            UUID.randomUUID(),
            queued.id(),
            OPERATION,
            1,
            CorrelationContext.current().orElseGet(UUID::randomUUID),
            1,
            now,
            commandData);
    String payload = serialize(command);
    outboxRepository.save(
        new OutboxMessage(
            UUID.randomUUID(),
            "CLIP",
            queued.id(),
            COMMAND_EVENT_TYPE,
            COMMAND_ROUTING_KEY,
            payload,
            0,
            now,
            null,
            null,
            now));
    return new ClipAggregate(queued, version);
  }

  @Transactional(readOnly = true)
  public ObjectStorage.PresignedDownload previewUrl(UUID userId, UUID clipId) {
    ClipAggregate aggregate = get(userId, clipId);
    if (aggregate.clip().status() != ClipStatus.READY
        || aggregate.clip().outputObjectKey() == null) {
      throw new ConflictException("Clip preview is not ready.");
    }
    if (objectStorage == null) {
      throw new ConflictException("Object storage is not configured.");
    }
    return objectStorage.presignDownload(aggregate.clip().outputObjectKey());
  }

  @Transactional
  public void handleStageUpdate(MessageEnvelope update) {
    validateEnvelope(update, STAGE_UPDATE_EVENT_TYPE, STAGE_FIELDS);
    ClipAggregate aggregate = currentAggregate(update.resourceId());
    int editVersion = aggregate.version().editVersion();
    ClipStatus status = parseWorkerStatus(requiredText(update.data(), "status"));
    if (status == null) {
      return;
    }
    if (aggregate.clip().status() == ClipStatus.READY
        || aggregate.clip().status() == ClipStatus.FAILED) {
      return;
    }
    Instant now = update.occurredAt();
    Clip next =
        switch (status) {
          case QUEUED -> aggregate.clip().queued(now);
          case PROCESSING -> aggregate.clip().processing(now);
          case FAILED ->
              aggregate
                  .clip()
                  .failed(
                      editVersion,
                      optionalText(update.data().get("errorCode"), "CLIP_GENERATION_FAILED"),
                      optionalText(update.data().get("errorMessage"), "Clip generation failed."),
                      now);
          case READY ->
              throw new IllegalArgumentException("READY belongs in ClipGenerationCompleted");
        };
    clipRepository.update(next);
  }

  @Transactional
  public void handleResult(MessageEnvelope result) {
    validateEnvelope(result, RESULT_EVENT_TYPE, RESULT_FIELDS);
    UUID clipId = requiredUuid(result.data(), "clipId");
    int editVersion = requiredPositiveInteger(result.data(), "editVersion");
    if (!clipId.equals(result.resourceId())) {
      throw new IllegalArgumentException("clipId does not match the event resource");
    }
    ClipAggregate aggregate = matchingAggregate(clipId, editVersion);
    String status = requiredText(result.data(), "status").toUpperCase();
    if (!"READY".equals(status) && !"FAILED".equals(status)) {
      throw new IllegalArgumentException("Unsupported clip generation result status: " + status);
    }
    if (aggregate.clip().status() == ClipStatus.READY
        || aggregate.clip().status() == ClipStatus.FAILED) {
      return;
    }
    Clip next;
    if ("READY".equals(status)) {
      String outputKey = requiredText(result.data(), "outputObjectKey");
      String expectedKey =
          ClipObjectKeys.preview(
              aggregate.clip().userId(),
              aggregate.clip().projectId(),
              aggregate.clip().id(),
              editVersion);
      if (!expectedKey.equals(outputKey)) {
        throw new IllegalArgumentException("Worker outputObjectKey does not match the server key");
      }
      int width = requiredPositiveInteger(result.data(), "width");
      int height = requiredPositiveInteger(result.data(), "height");
      BigDecimal duration = requiredPositiveDecimal(result.data(), "durationSeconds");
      AspectRatio aspectRatio = parseAspectRatio(requiredText(result.data(), "aspectRatio"));
      if (aspectRatio != aggregate.version().aspectRatio()) {
        throw new IllegalArgumentException("Worker aspectRatio does not match the edit version");
      }
      next =
          aggregate
              .clip()
              .ready(
                  editVersion,
                  outputKey,
                  duration,
                  width,
                  height,
                  aspectRatio,
                  result.occurredAt());
    } else {
      next =
          aggregate
              .clip()
              .failed(
                  editVersion,
                  optionalText(result.data().get("errorCode"), "CLIP_GENERATION_FAILED"),
                  optionalText(result.data().get("errorMessage"), "Clip generation failed."),
                  result.occurredAt());
    }
    clipRepository.update(next);
  }

  private ClipAggregate matchingAggregate(UUID clipId, int editVersion) {
    ClipAggregate aggregate = currentAggregate(clipId);
    if (aggregate.version().editVersion() != editVersion) {
      throw new IllegalArgumentException("Worker event does not match the current edit version");
    }
    return aggregate;
  }

  private ClipAggregate currentAggregate(UUID clipId) {
    return clipRepository
        .findById(clipId)
        .orElseThrow(() -> new ResourceNotFoundException("Clip for worker event not found."));
  }

  private Map<String, Object> generationData(Clip clip, ClipVersion version) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("clipId", clip.id());
    data.put("userId", clip.userId());
    data.put("projectId", clip.projectId());
    data.put("videoId", clip.videoId());
    data.put("pipelineVersion", 1);
    data.put("editVersion", version.editVersion());
    data.put("sourceObjectKey", ownedSourceKey(clip.videoId(), clip.userId()));
    data.put(
        "outputObjectKey",
        ClipObjectKeys.preview(clip.userId(), clip.projectId(), clip.id(), version.editVersion()));
    data.put("startSeconds", version.startSeconds());
    data.put("endSeconds", version.endSeconds());
    data.put("aspectRatio", version.aspectRatio().value());
    data.put("captionPreset", version.captionPreset().name());
    data.put("captionStyle", styleData(version.captionStyle()));
    data.put("captionCues", cueData(version.captionCues()));
    return data;
  }

  private String ownedSourceKey(UUID videoId, UUID userId) {
    return videoRepository
        .findByIdForUser(videoId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("Video not found."))
        .objectKey();
  }

  private static List<Map<String, Object>> cueData(List<CaptionCue> cues) {
    List<Map<String, Object>> values = new ArrayList<>();
    for (int sequence = 0; sequence < cues.size(); sequence++) {
      CaptionCue cue = cues.get(sequence);
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("sequence", sequence);
      data.put("text", cue.text());
      data.put("startSeconds", cue.startSeconds());
      data.put("endSeconds", cue.endSeconds());
      values.add(data);
    }
    return values;
  }

  private static Map<String, Object> styleData(CaptionStyle style) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("fontFamily", style.fontFamily());
    data.put("fontSize", style.fontSize());
    data.put("fontWeight", style.fontWeight());
    data.put("textColor", style.textColor());
    data.put("backgroundColor", style.backgroundColor());
    data.put("backgroundOpacity", style.backgroundOpacity());
    data.put("position", style.position().name());
    data.put("animation", style.animation().name());
    return data;
  }

  private ClipCandidate ownedCandidate(UUID userId, UUID videoId, UUID candidateId) {
    ClipCandidate candidate =
        clipAnalysisRepository
            .findCandidateForUser(candidateId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Clip candidate not found."));
    if (!candidate.videoId().equals(videoId)) {
      throw new ResourceNotFoundException("Clip candidate not found.");
    }
    return candidate;
  }

  private Video ownedReadyVideo(UUID userId, UUID videoId) {
    Video video =
        videoRepository
            .findByIdForUser(videoId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Video not found."));
    if (video.status() != VideoUploadStatus.READY || video.durationSeconds() == null) {
      throw new ConflictException("Video must be ready with duration metadata.");
    }
    return video;
  }

  private Transcription completedTranscription(UUID videoId, UUID userId) {
    return transcriptionRepository
        .findLatestForUser(videoId, userId)
        .filter(value -> value.status() == TranscriptionStatus.COMPLETED)
        .orElseThrow(() -> new ConflictException("A completed transcription is required."));
  }

  private static void validateAgainstVideo(BigDecimal start, BigDecimal end, Video video) {
    if (start == null || end == null || start.signum() < 0 || end.compareTo(start) <= 0) {
      throw new ValidationException("Clip interval must be positive.");
    }
    if (end.subtract(start).compareTo(BigDecimal.valueOf(90)) > 0) {
      throw new ValidationException("Clip interval must be at most 90 seconds.");
    }
    if (video.durationSeconds() != null && end.compareTo(video.durationSeconds()) > 0) {
      throw new ValidationException("Clip interval must be inside the video.");
    }
  }

  private static List<CaptionCue> cuesFor(
      Transcription transcription, BigDecimal clipStart, BigDecimal clipEnd) {
    List<TranscriptionWord> words =
        transcription.segments().stream().flatMap(segment -> segment.words().stream()).toList();
    if (!words.isEmpty()) {
      List<CaptionCue> cues = new ArrayList<>();
      for (TranscriptionWord word : words) {
        CaptionCue cue = overlapWord(word, clipStart, clipEnd);
        if (cue != null) {
          cues.add(cue);
        }
      }
      return cues;
    }
    List<CaptionCue> cues = new ArrayList<>();
    for (TranscriptionSegment segment : transcription.segments()) {
      CaptionCue cue = overlapSegment(segment, clipStart, clipEnd);
      if (cue != null) {
        cues.add(cue);
      }
    }
    return cues;
  }

  private static CaptionCue overlapWord(
      TranscriptionWord word, BigDecimal clipStart, BigDecimal clipEnd) {
    return overlap(word.text(), word.startSeconds(), word.endSeconds(), clipStart, clipEnd);
  }

  private static CaptionCue overlapSegment(
      TranscriptionSegment segment, BigDecimal clipStart, BigDecimal clipEnd) {
    return overlap(
        segment.text(), segment.startSeconds(), segment.endSeconds(), clipStart, clipEnd);
  }

  private static CaptionCue overlap(
      String text,
      BigDecimal sourceStart,
      BigDecimal sourceEnd,
      BigDecimal clipStart,
      BigDecimal clipEnd) {
    if (sourceEnd.compareTo(clipStart) <= 0 || sourceStart.compareTo(clipEnd) >= 0) {
      return null;
    }
    BigDecimal relativeStart = sourceStart.max(clipStart).subtract(clipStart);
    BigDecimal relativeEnd = sourceEnd.min(clipEnd).subtract(clipStart);
    if (relativeEnd.compareTo(relativeStart) <= 0) {
      return null;
    }
    return new CaptionCue(UUID.randomUUID(), text, relativeStart, relativeEnd);
  }

  private static CaptionStyle updatedStyle(
      ClipVersion current, ClipEditCommand command, CaptionPreset preset) {
    CaptionStyle base =
        command.captionPreset() != null && command.hasStyleOverride()
            ? current.captionStyle()
            : command.captionPreset() != null ? preset.defaultStyle() : current.captionStyle();
    return new CaptionStyle(
        command.fontFamily() == null ? base.fontFamily() : command.fontFamily(),
        command.fontSize() == null ? base.fontSize() : command.fontSize(),
        command.fontWeight() == null ? base.fontWeight() : command.fontWeight(),
        command.textColor() == null ? base.textColor() : command.textColor(),
        command.backgroundColor() == null ? base.backgroundColor() : command.backgroundColor(),
        command.backgroundOpacity() == null
            ? base.backgroundOpacity()
            : command.backgroundOpacity(),
        command.position() == null
            ? base.position()
            : CaptionPosition.fromValue(command.position()),
        command.animation() == null
            ? base.animation()
            : CaptionAnimation.fromValue(command.animation()));
  }

  private static CaptionStyle validatedStyle(
      ClipVersion current, ClipEditCommand command, CaptionPreset preset) {
    try {
      return updatedStyle(current, command, preset);
    } catch (IllegalArgumentException exception) {
      throw new ValidationException(exception.getMessage());
    }
  }

  private static List<CaptionCue> manualCue(String text, BigDecimal duration) {
    try {
      return List.of(new CaptionCue(UUID.randomUUID(), text, BigDecimal.ZERO, duration));
    } catch (IllegalArgumentException exception) {
      throw new ValidationException(exception.getMessage());
    }
  }

  private static void validateEnvelope(
      MessageEnvelope envelope, String eventType, Set<String> allowedFields) {
    if (envelope.kind() != MessageKind.EVENT
        || envelope.eventVersion() != 1
        || envelope.version() != 1
        || !eventType.equals(envelope.eventType())
        || !OPERATION.equals(envelope.operation())) {
      throw new IllegalArgumentException("Unsupported clip generation worker event");
    }
    for (String key : envelope.data().keySet()) {
      if (!allowedFields.contains(key)) {
        throw new IllegalArgumentException("Unknown clip generation event field: " + key);
      }
    }
  }

  private static AspectRatio parseAspectRatio(String value) {
    try {
      return AspectRatio.fromValue(value);
    } catch (IllegalArgumentException exception) {
      throw new ValidationException(exception.getMessage());
    }
  }

  private static CaptionPreset parseCaptionPreset(String value) {
    try {
      return CaptionPreset.fromValue(value);
    } catch (IllegalArgumentException exception) {
      throw new ValidationException(exception.getMessage());
    }
  }

  private static ClipStatus parseStatus(String value) {
    try {
      return ClipStatus.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException exception) {
      throw new ValidationException("Unsupported clip status: " + value);
    }
  }

  private static ClipStatus parseWorkerStatus(String value) {
    return switch (value.trim().toUpperCase()) {
      case "QUEUED" -> ClipStatus.QUEUED;
      case "PROCESSING", "RETRYING" -> ClipStatus.PROCESSING;
      case "FAILED" -> ClipStatus.FAILED;
      case "COMPLETED" -> null;
      default -> throw new ValidationException("Unsupported clip worker status: " + value);
    };
  }

  private static void validatePage(int page, int size) {
    if (page < 0 || size < 1 || size > 100) {
      throw new ValidationException("page must be non-negative and size must be between 1 and 100");
    }
  }

  private String serialize(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new ProcessingException("Could not serialize a clip generation message.");
    }
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

  private static int requiredPositiveInteger(Map<String, Object> data, String field) {
    Object value = data.get(field);
    if (!(value instanceof Number number)
        || number.intValue() <= 0
        || number.doubleValue() != number.intValue()) {
      throw new IllegalArgumentException(field + " must be a positive integer");
    }
    return number.intValue();
  }

  private static BigDecimal requiredPositiveDecimal(Map<String, Object> data, String field) {
    Object value = data.get(field);
    if (!(value instanceof Number number)) {
      throw new IllegalArgumentException(field + " must be a positive number");
    }
    BigDecimal decimal =
        number instanceof Double || number instanceof Float
            ? BigDecimal.valueOf(number.doubleValue())
            : new BigDecimal(number.toString());
    if (decimal.signum() <= 0) {
      throw new IllegalArgumentException(field + " must be a positive number");
    }
    return decimal;
  }

  private static String requiredText(Map<String, Object> data, String field) {
    return optionalText(data.get(field), null, field);
  }

  private static String optionalText(Object value, String fallback) {
    return optionalText(value, fallback, "value");
  }

  private static String optionalText(Object value, String fallback, String field) {
    if (value == null) {
      if (fallback != null) {
        return fallback;
      }
      throw new IllegalArgumentException(field + " is required");
    }
    if (!(value instanceof String string) || string.isBlank()) {
      throw new IllegalArgumentException(field + " must be non-blank text");
    }
    return string;
  }
}
