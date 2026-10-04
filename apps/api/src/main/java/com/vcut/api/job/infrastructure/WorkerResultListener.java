package com.vcut.api.job.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.vcut.api.clip.application.ClipAnalysisApplicationService;
import com.vcut.api.clip.application.ClipApplicationService;
import com.vcut.api.clip.application.FinalRenderApplicationService;
import com.vcut.api.job.application.JobApplicationService;
import com.vcut.api.shared.correlation.CorrelationContext;
import com.vcut.api.shared.correlation.CorrelationTracing;
import com.vcut.api.shared.messaging.MessageCompatibility;
import com.vcut.api.shared.messaging.MessageEnvelope;
import com.vcut.api.transcription.application.TranscriptionApplicationService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "vcut.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class WorkerResultListener {

  private final ObjectMapper objectMapper;
  private final JobApplicationService jobApplicationService;
  private final TranscriptionApplicationService transcriptionApplicationService;
  private final ClipAnalysisApplicationService clipAnalysisApplicationService;
  private final ClipApplicationService clipApplicationService;
  private final FinalRenderApplicationService finalRenderApplicationService;
  private final Tracer tracer;

  @Autowired
  public WorkerResultListener(
      ObjectMapper objectMapper,
      JobApplicationService jobApplicationService,
      TranscriptionApplicationService transcriptionApplicationService,
      ClipAnalysisApplicationService clipAnalysisApplicationService,
      @Nullable ClipApplicationService clipApplicationService,
      @Nullable FinalRenderApplicationService finalRenderApplicationService,
      Tracer tracer) {
    this.objectMapper = objectMapper;
    this.jobApplicationService = jobApplicationService;
    this.transcriptionApplicationService = transcriptionApplicationService;
    this.clipAnalysisApplicationService = clipAnalysisApplicationService;
    this.clipApplicationService = clipApplicationService;
    this.finalRenderApplicationService = finalRenderApplicationService;
    this.tracer = tracer;
  }

  public WorkerResultListener(
      ObjectMapper objectMapper,
      JobApplicationService jobApplicationService,
      TranscriptionApplicationService transcriptionApplicationService,
      ClipAnalysisApplicationService clipAnalysisApplicationService,
      @Nullable ClipApplicationService clipApplicationService,
      @Nullable FinalRenderApplicationService finalRenderApplicationService) {
    this(
        objectMapper,
        jobApplicationService,
        transcriptionApplicationService,
        clipAnalysisApplicationService,
        clipApplicationService,
        finalRenderApplicationService,
        io.opentelemetry.api.OpenTelemetry.noop().getTracer("com.vcut.api"));
  }

  public WorkerResultListener(
      ObjectMapper objectMapper,
      JobApplicationService jobApplicationService,
      TranscriptionApplicationService transcriptionApplicationService,
      ClipAnalysisApplicationService clipAnalysisApplicationService,
      @Nullable ClipApplicationService clipApplicationService) {
    this(
        objectMapper,
        jobApplicationService,
        transcriptionApplicationService,
        clipAnalysisApplicationService,
        clipApplicationService,
        null);
  }

  public WorkerResultListener(
      ObjectMapper objectMapper,
      JobApplicationService jobApplicationService,
      TranscriptionApplicationService transcriptionApplicationService,
      ClipAnalysisApplicationService clipAnalysisApplicationService) {
    this(
        objectMapper,
        jobApplicationService,
        transcriptionApplicationService,
        clipAnalysisApplicationService,
        null,
        null);
  }

  public WorkerResultListener(
      ObjectMapper objectMapper,
      JobApplicationService jobApplicationService,
      TranscriptionApplicationService transcriptionApplicationService) {
    this(objectMapper, jobApplicationService, transcriptionApplicationService, null, null, null);
  }

  @RabbitListener(
      queues = JobMessagingConfiguration.RESULT_QUEUE,
      containerFactory = "rabbitListenerContainerFactory")
  public void receive(Message message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag)
      throws IOException {
    try {
      MessageEnvelope envelope = objectMapper.readValue(message.getBody(), MessageEnvelope.class);
      MessageCompatibility.requireSupported(envelope);
      CorrelationContext.set(envelope.correlationId());
      Span span =
          CorrelationTracing.startSpan(
              tracer,
              "messaging.result",
              envelope.correlationId(),
              envelope.traceparent(),
              SpanKind.CONSUMER);
      span.setAttribute("messaging.system", "rabbitmq");
      span.setAttribute("messaging.message.id", envelope.eventId().toString());
      span.setAttribute("messaging.operation.name", "process");
      span.setAttribute("vcut.command.type", envelope.operation());
      try (Scope ignored = span.makeCurrent()) {
        if (TranscriptionApplicationService.OPERATION.equals(envelope.operation())) {
          if (TranscriptionApplicationService.STAGE_UPDATE_EVENT_TYPE.equals(
              envelope.eventType())) {
            transcriptionApplicationService.handleStageUpdate(envelope);
          } else {
            transcriptionApplicationService.handleResult(envelope);
          }
        } else if (ClipApplicationService.OPERATION.equals(envelope.operation())) {
          if (clipApplicationService == null) {
            throw new IllegalStateException("Clip generation listener is not configured");
          }
          if (ClipApplicationService.STAGE_UPDATE_EVENT_TYPE.equals(envelope.eventType())) {
            clipApplicationService.handleStageUpdate(envelope);
          } else {
            clipApplicationService.handleResult(envelope);
          }
        } else if (FinalRenderApplicationService.OPERATION.equals(envelope.operation())) {
          if (finalRenderApplicationService == null) {
            throw new IllegalStateException("Final render listener is not configured");
          }
          if (FinalRenderApplicationService.STAGE_UPDATE_EVENT_TYPE.equals(envelope.eventType())) {
            finalRenderApplicationService.handleStageUpdate(envelope);
          } else {
            finalRenderApplicationService.handleResult(envelope);
          }
        } else if (ClipAnalysisApplicationService.OPERATION.equals(envelope.operation())) {
          if (clipAnalysisApplicationService == null) {
            throw new IllegalStateException("Clip analysis listener is not configured");
          }
          if (ClipAnalysisApplicationService.STAGE_UPDATE_EVENT_TYPE.equals(envelope.eventType())) {
            clipAnalysisApplicationService.handleStageUpdate(envelope);
          } else {
            clipAnalysisApplicationService.handleResult(envelope);
          }
        } else if (JobApplicationService.STAGE_UPDATE_EVENT_TYPE.equals(envelope.eventType())) {
          jobApplicationService.handleStageUpdate(envelope);
        } else {
          jobApplicationService.handleResult(envelope);
        }
        channel.basicAck(tag, false);
      } catch (Exception exception) {
        CorrelationTracing.markError(span, exception);
        throw exception;
      } finally {
        span.end();
        CorrelationContext.clear();
      }
    } catch (Exception exception) {
      channel.basicNack(tag, false, false);
    }
  }
}
