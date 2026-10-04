package com.vcut.api.clip.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vcut.api.clip.domain.AspectRatio;
import com.vcut.api.clip.domain.CaptionCue;
import com.vcut.api.clip.domain.CaptionStyle;
import com.vcut.api.clip.domain.Clip;
import com.vcut.api.clip.domain.ClipRender;
import com.vcut.api.clip.domain.ClipVersion;
import com.vcut.api.clip.domain.RenderStatus;
import com.vcut.api.job.application.OutboxRepository;
import com.vcut.api.job.domain.OutboxMessage;
import com.vcut.api.shared.correlation.CorrelationContext;
import com.vcut.api.shared.errors.ConflictException;
import com.vcut.api.shared.errors.ProcessingException;
import com.vcut.api.shared.errors.ResourceNotFoundException;
import com.vcut.api.shared.errors.ValidationException;
import com.vcut.api.shared.messaging.MessageEnvelope;
import com.vcut.api.shared.messaging.MessageKind;
import com.vcut.api.usage.application.RetentionApplicationService;
import com.vcut.api.usage.application.UsageApplicationService;
import com.vcut.api.usage.domain.RetentionAssetType;
import com.vcut.api.usage.domain.UsageMetrics;
import com.vcut.api.video.application.ObjectStorage;
import com.vcut.api.video.application.VideoRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinalRenderApplicationService {

  public static final String OPERATION = "FINAL_RENDER";
  public static final String COMMAND_EVENT_TYPE = "FinalRenderRequested";
  public static final String RESULT_EVENT_TYPE = "FinalRenderCompleted";
  public static final String STAGE_UPDATE_EVENT_TYPE = "StageRunUpdated";
  public static final String COMMAND_ROUTING_KEY = "pipeline.video.final-render";

  private static final Pattern ERROR_CODE = Pattern.compile("^[A-Z0-9_]{1,128}$");
  private static final Set<String> RESULT_FIELDS =
      Set.of(
          "renderId",
          "clipId",
          "editVersion",
          "status",
          "outputObjectKey",
          "thumbnailObjectKey",
          "durationSeconds",
          "width",
          "height",
          "aspectRatio",
          "errorCode",
          "errorMessage");
  private static final Set<String> STAGE_FIELDS =
      Set.of("status", "progress", "attempt", "errorCode", "errorMessage");

  private final ClipRepository clipRepository;
  private final VideoRepository videoRepository;
  private final ClipRenderRepository renderRepository;
  private final OutboxRepository outboxRepository;
  private final ObjectStorage objectStorage;
  private final ObjectMapper objectMapper;
  private final RetentionApplicationService retentionApplicationService;
  private final UsageApplicationService usageApplicationService;
  private final Clock clock;

  @Autowired
  public FinalRenderApplicationService(
      ClipRepository clipRepository,
      VideoRepository videoRepository,
      ClipRenderRepository renderRepository,
      OutboxRepository outboxRepository,
      @Nullable ObjectStorage objectStorage,
      ObjectMapper objectMapper,
      RetentionApplicationService retentionApplicationService,
      UsageApplicationService usageApplicationService) {
    this(
        clipRepository,
        videoRepository,
        renderRepository,
        outboxRepository,
        objectStorage,
        objectMapper,
        retentionApplicationService,
        usageApplicationService,
        Clock.systemUTC());
  }

  public FinalRenderApplicationService(
      ClipRepository clipRepository,
      VideoRepository videoRepository,
      ClipRenderRepository renderRepository,
      OutboxRepository outboxRepository,
      @Nullable ObjectStorage objectStorage,
      ObjectMapper objectMapper) {
    this(
        clipRepository,
        videoRepository,
        renderRepository,
        outboxRepository,
        objectStorage,
        objectMapper,
        null,
        null,
        Clock.systemUTC());
  }

  FinalRenderApplicationService(
      ClipRepository clipRepository,
      VideoRepository videoRepository,
      ClipRenderRepository renderRepository,
      OutboxRepository outboxRepository,
      ObjectStorage objectStorage,
      ObjectMapper objectMapper,
      Clock clock) {
    this(
        clipRepository,
        videoRepository,
        renderRepository,
        outboxRepository,
        objectStorage,
        objectMapper,
        null,
        null,
        clock);
  }

  FinalRenderApplicationService(
      ClipRepository clipRepository,
      VideoRepository videoRepository,
      ClipRenderRepository renderRepository,
      OutboxRepository outboxRepository,
      ObjectStorage objectStorage,
      ObjectMapper objectMapper,
      RetentionApplicationService retentionApplicationService,
      UsageApplicationService usageApplicationService,
      Clock clock) {
    this.clipRepository = clipRepository;
    this.videoRepository = videoRepository;
    this.renderRepository = renderRepository;
    this.outboxRepository = outboxRepository;
    this.objectStorage = objectStorage;
    this.objectMapper = objectMapper;
    this.retentionApplicationService = retentionApplicationService;
    this.usageApplicationService = usageApplicationService;
    this.clock = clock;
  }

  @Transactional
  public ClipRender request(UUID userId, UUID clipId, int editVersion) {
    ClipAggregate aggregate = ownedClip(clipId, userId);
    requireCurrentVersion(aggregate.version(), editVersion);
    return renderRepository
        .findByClipAndVersionForUser(clipId, editVersion, userId)
        .orElseGet(() -> createAndPublish(aggregate.clip(), aggregate.version(), userId));
  }

  @Transactional(readOnly = true)
  public List<ClipRender> list(UUID userId, UUID clipId) {
    ownedClip(clipId, userId);
    return renderRepository.findByClipForUser(clipId, userId);
  }

  @Transactional
  public ClipRender retry(UUID userId, UUID renderId) {
    ClipRender current = ownedRender(renderId, userId);
    if (current.status() != RenderStatus.FAILED) {
      return current;
    }
    ClipAggregate aggregate = ownedClip(current.clipId(), userId);
    requireCurrentVersion(aggregate.version(), current.editVersion());
    Instant now = clock.instant();
    if (!renderRepository.claimRetry(renderId, userId, now)) {
      return ownedRender(renderId, userId);
    }
    ClipRender queued = current.retry(now);
    renderRepository.update(queued);
    publishCommand(queued, aggregate.clip(), aggregate.version(), now);
    return queued;
  }

  @Transactional(readOnly = true)
  public ObjectStorage.PresignedDownload downloadUrl(UUID userId, UUID renderId) {
    ClipRender render = readyRender(renderId, userId);
    return storage().presignDownload(render.outputObjectKey());
  }

  @Transactional(readOnly = true)
  public ObjectStorage.PresignedDownload thumbnailUrl(UUID userId, UUID renderId) {
    ClipRender render = readyRender(renderId, userId);
    return storage().presignDownload(render.thumbnailObjectKey());
  }

  @Transactional
  public void handleStageUpdate(MessageEnvelope update) {
    validateEnvelope(update, STAGE_UPDATE_EVENT_TYPE, STAGE_FIELDS);
    ClipRender current = currentRender(update.resourceId());
    if (current.status() == RenderStatus.READY || current.status() == RenderStatus.FAILED) {
      return;
    }
    if ("COMPLETED".equalsIgnoreCase(requiredText(update.data(), "status"))) {
      return;
    }
    RenderStatus status = parseWorkerStatus(requiredText(update.data(), "status"));
    Instant now = update.occurredAt();
    ClipRender next =
        switch (status) {
          case PROCESSING -> current.processing(progress(update.data()), now);
          case FAILED ->
              current.failed(
                  safeErrorCode(update.data().get("errorCode"), "FINAL_RENDER_FAILED"),
                  safeErrorMessage(update.data().get("errorMessage"), "Final render failed."),
                  now);
          case QUEUED -> current;
          case READY -> throw new IllegalArgumentException("READY belongs in FinalRenderCompleted");
        };
    if (next != current) {
      renderRepository.update(next);
    }
  }

  @Transactional
  public void handleResult(MessageEnvelope result) {
    validateEnvelope(result, RESULT_EVENT_TYPE, RESULT_FIELDS);
    UUID renderId = requiredUuid(result.data(), "renderId");
    UUID clipId = requiredUuid(result.data(), "clipId");
    int editVersion = requiredPositiveInteger(result.data(), "editVersion");
    ClipRender current = currentRender(renderId);
    if (!current.clipId().equals(clipId) || current.editVersion() != editVersion) {
      throw new IllegalArgumentException("Final render result does not match the requested render");
    }
    if (current.status() == RenderStatus.READY || current.status() == RenderStatus.FAILED) {
      return;
    }
    String status = requiredText(result.data(), "status").toUpperCase();
    ClipRender next;
    if ("READY".equals(status)) {
      ClipAggregate clip = ownedClip(current.clipId(), current.userId());
      if (clip.version().editVersion() != current.editVersion()) {
        throw new IllegalArgumentException("Final render result is stale");
      }
      String outputKey = requiredText(result.data(), "outputObjectKey");
      String thumbnailKey = requiredText(result.data(), "thumbnailObjectKey");
      if (!ClipRenderObjectKeys.finalVideo(
                  current.userId(), current.projectId(), current.clipId(), current.editVersion())
              .equals(outputKey)
          || !ClipRenderObjectKeys.thumbnail(
                  current.userId(), current.projectId(), current.clipId(), current.editVersion())
              .equals(thumbnailKey)) {
        throw new IllegalArgumentException("Final render object keys do not match server keys");
      }
      AspectRatio aspectRatio = parseAspectRatio(requiredText(result.data(), "aspectRatio"));
      if (aspectRatio != clip.version().aspectRatio()) {
        throw new IllegalArgumentException("Final render aspect ratio does not match edit version");
      }
      next =
          current.ready(
              outputKey,
              thumbnailKey,
              requiredPositiveDecimal(result.data(), "durationSeconds"),
              requiredPositiveInteger(result.data(), "width"),
              requiredPositiveInteger(result.data(), "height"),
              aspectRatio,
              result.occurredAt());
    } else if ("FAILED".equals(status)) {
      next =
          current.failed(
              safeErrorCode(result.data().get("errorCode"), "FINAL_RENDER_FAILED"),
              safeErrorMessage(result.data().get("errorMessage"), "Final render failed."),
              result.occurredAt());
    } else {
      throw new IllegalArgumentException("Unsupported final render result status: " + status);
    }
    renderRepository.update(next);
    if (next.status() == RenderStatus.READY) {
      recordFinalRenderUsage(next);
    }
  }

  private void recordFinalRenderUsage(ClipRender render) {
    long storageBytes = 0;
    if (objectStorage != null) {
      for (String objectKey : List.of(render.outputObjectKey(), render.thumbnailObjectKey())) {
        var metadata = objectStorage.head(objectKey);
        if (metadata.isEmpty()) {
          continue;
        }
        storageBytes = Math.addExact(storageBytes, metadata.get().contentLength());
        if (retentionApplicationService != null) {
          retentionApplicationService.register(
              render.userId(),
              render.projectId(),
              objectKey,
              objectKey.equals(render.outputObjectKey())
                  ? RetentionAssetType.FINAL
                  : RetentionAssetType.THUMBNAIL,
              metadata.get().contentLength());
        }
      }
    }
    if (usageApplicationService != null) {
      usageApplicationService.recordMetrics(
          render.userId(),
          render.clipId(),
          OPERATION,
          render.editVersion(),
          new UsageMetrics(
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              0,
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              retentionApplicationService == null ? storageBytes : 0,
              0,
              1),
          "SUCCEEDED");
    }
  }

  private ClipRender createAndPublish(Clip clip, ClipVersion version, UUID userId) {
    if (usageApplicationService != null) {
      usageApplicationService.assertRenderAllowed(
          userId,
          version.endSeconds().subtract(version.startSeconds()),
          outputWidth(version),
          outputHeight(version),
          estimatedOutputBytes(version));
    }
    Instant now = clock.instant();
    ClipRender render =
        ClipRender.queued(
            UUID.randomUUID(), clip.id(), userId, clip.projectId(), version.editVersion(), now);
    renderRepository.save(render);
    publishCommand(render, clip, version, now);
    return render;
  }

  private static int outputWidth(ClipVersion version) {
    return version.aspectRatio() == AspectRatio.PORTRAIT ? 1080 : 1920;
  }

  private static int outputHeight(ClipVersion version) {
    return version.aspectRatio() == AspectRatio.PORTRAIT ? 1920 : 1080;
  }

  private static long estimatedOutputBytes(ClipVersion version) {
    BigDecimal seconds = version.endSeconds().subtract(version.startSeconds());
    long pixels = (long) outputWidth(version) * outputHeight(version);
    return seconds
        .multiply(BigDecimal.valueOf(pixels))
        .multiply(BigDecimal.valueOf(3))
        .divide(BigDecimal.valueOf(8), 0, java.math.RoundingMode.CEILING)
        .longValueExact();
  }

  private void publishCommand(ClipRender render, Clip clip, ClipVersion version, Instant now) {
    MessageEnvelope command =
        new MessageEnvelope(
            MessageKind.COMMAND,
            UUID.randomUUID(),
            COMMAND_EVENT_TYPE,
            1,
            render.id(),
            render.id(),
            OPERATION,
            1,
            CorrelationContext.current().orElseGet(UUID::randomUUID),
            1,
            now,
            renderData(render, clip, version));
    outboxRepository.save(
        new OutboxMessage(
            UUID.randomUUID(),
            "CLIP_RENDER",
            render.id(),
            COMMAND_EVENT_TYPE,
            COMMAND_ROUTING_KEY,
            serialize(command),
            0,
            now,
            null,
            null,
            now));
  }

  private Map<String, Object> renderData(ClipRender render, Clip clip, ClipVersion version) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("renderId", render.id());
    data.put("clipId", clip.id());
    data.put("userId", clip.userId());
    data.put("projectId", clip.projectId());
    data.put("videoId", clip.videoId());
    data.put("pipelineVersion", 1);
    data.put("editVersion", version.editVersion());
    data.put("sourceObjectKey", clipSourceKey(clip.videoId(), clip.userId()));
    data.put(
        "outputObjectKey",
        ClipRenderObjectKeys.finalVideo(
            clip.userId(), clip.projectId(), clip.id(), version.editVersion()));
    data.put(
        "thumbnailObjectKey",
        ClipRenderObjectKeys.thumbnail(
            clip.userId(), clip.projectId(), clip.id(), version.editVersion()));
    data.put("startSeconds", version.startSeconds());
    data.put("endSeconds", version.endSeconds());
    data.put("aspectRatio", version.aspectRatio().value());
    data.put("cropSettings", cropData(version));
    data.put("captionPreset", version.captionPreset().name());
    data.put("captionStyle", styleData(version.captionStyle()));
    data.put("captionCues", cueData(version.captionCues()));
    return data;
  }

  private String clipSourceKey(UUID videoId, UUID userId) {
    return videoRepository
        .findByIdForUser(videoId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("Video not found."))
        .objectKey();
  }

  private ClipAggregate ownedClip(UUID clipId, UUID userId) {
    return clipRepository
        .findByIdForUser(clipId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("Clip not found."));
  }

  private ClipRender ownedRender(UUID renderId, UUID userId) {
    return renderRepository
        .findByIdForUser(renderId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("Render not found."));
  }

  private ClipRender currentRender(UUID renderId) {
    return renderRepository
        .findById(renderId)
        .orElseThrow(() -> new ResourceNotFoundException("Render for worker event not found."));
  }

  private ClipRender readyRender(UUID renderId, UUID userId) {
    ClipRender render = ownedRender(renderId, userId);
    if (render.status() != RenderStatus.READY
        || render.outputObjectKey() == null
        || render.thumbnailObjectKey() == null) {
      throw new ConflictException("Final render is not ready.");
    }
    return render;
  }

  private ObjectStorage storage() {
    if (objectStorage == null) {
      throw new ConflictException("Object storage is not configured.");
    }
    return objectStorage;
  }

  private static void requireCurrentVersion(ClipVersion version, int requestedVersion) {
    if (version.editVersion() != requestedVersion) {
      throw new ConflictException("Render request does not match the current edit version.");
    }
  }

  private static Map<String, Object> cropData(ClipVersion version) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("x", version.crop().x());
    data.put("y", version.crop().y());
    data.put("zoom", version.crop().zoom());
    return data;
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

  private static void validateEnvelope(
      MessageEnvelope envelope, String eventType, Set<String> allowedFields) {
    if (envelope.kind() != MessageKind.EVENT
        || envelope.eventVersion() != 1
        || envelope.version() != 1
        || !eventType.equals(envelope.eventType())
        || !OPERATION.equals(envelope.operation())) {
      throw new IllegalArgumentException("Unsupported final render worker event");
    }
    for (String key : envelope.data().keySet()) {
      if (!allowedFields.contains(key)) {
        throw new IllegalArgumentException("Unknown final render event field: " + key);
      }
    }
  }

  private static RenderStatus parseWorkerStatus(String value) {
    return switch (value.trim().toUpperCase()) {
      case "PROCESSING", "RETRYING" -> RenderStatus.PROCESSING;
      case "FAILED" -> RenderStatus.FAILED;
      case "QUEUED" -> RenderStatus.QUEUED;
      case "COMPLETED" -> RenderStatus.READY;
      default -> throw new ValidationException("Unsupported final render worker status: " + value);
    };
  }

  private static int progress(Map<String, Object> data) {
    Object value = data.get("progress");
    if (!(value instanceof Number number)
        || number.intValue() < 0
        || number.intValue() > 100
        || number.doubleValue() != number.intValue()) {
      throw new ValidationException("Worker progress must be an integer between 0 and 100");
    }
    return number.intValue();
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
    Object value = data.get(field);
    if (!(value instanceof String string) || string.isBlank()) {
      throw new IllegalArgumentException(field + " must be non-blank text");
    }
    return string;
  }

  private static String safeErrorCode(Object value, String fallback) {
    if (!(value instanceof String string)) {
      return fallback;
    }
    String normalized = string.trim().toUpperCase();
    return ERROR_CODE.matcher(normalized).matches() ? normalized : fallback;
  }

  private static String safeErrorMessage(Object value, String fallback) {
    if (!(value instanceof String string)) {
      return fallback;
    }
    String sanitized = string.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
    if (sanitized.isBlank()) {
      return fallback;
    }
    return sanitized.length() > 1000 ? sanitized.substring(0, 1000) : sanitized;
  }

  private static AspectRatio parseAspectRatio(String value) {
    try {
      return AspectRatio.fromValue(value);
    } catch (IllegalArgumentException exception) {
      throw new ValidationException(exception.getMessage());
    }
  }

  private String serialize(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new ProcessingException("Could not serialize a final render message.");
    }
  }
}
