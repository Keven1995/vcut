package com.vcut.api.job.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.vcut.api.job.application.JobApplicationService;
import com.vcut.api.shared.messaging.MessageCompatibility;
import com.vcut.api.shared.messaging.MessageEnvelope;
import com.vcut.api.transcription.application.TranscriptionApplicationService;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "vcut.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class WorkerResultListener {

  private final ObjectMapper objectMapper;
  private final JobApplicationService jobApplicationService;
  private final TranscriptionApplicationService transcriptionApplicationService;

  public WorkerResultListener(
      ObjectMapper objectMapper,
      JobApplicationService jobApplicationService,
      TranscriptionApplicationService transcriptionApplicationService) {
    this.objectMapper = objectMapper;
    this.jobApplicationService = jobApplicationService;
    this.transcriptionApplicationService = transcriptionApplicationService;
  }

  @RabbitListener(
      queues = JobMessagingConfiguration.RESULT_QUEUE,
      containerFactory = "rabbitListenerContainerFactory")
  public void receive(Message message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag)
      throws IOException {
    try {
      MessageEnvelope envelope = objectMapper.readValue(message.getBody(), MessageEnvelope.class);
      MessageCompatibility.requireSupported(envelope);
      if (TranscriptionApplicationService.OPERATION.equals(envelope.operation())) {
        if (TranscriptionApplicationService.STAGE_UPDATE_EVENT_TYPE.equals(envelope.eventType())) {
          transcriptionApplicationService.handleStageUpdate(envelope);
        } else {
          transcriptionApplicationService.handleResult(envelope);
        }
      } else if (JobApplicationService.STAGE_UPDATE_EVENT_TYPE.equals(envelope.eventType())) {
        jobApplicationService.handleStageUpdate(envelope);
      } else {
        jobApplicationService.handleResult(envelope);
      }
      channel.basicAck(tag, false);
    } catch (Exception exception) {
      channel.basicNack(tag, false, false);
    }
  }
}
