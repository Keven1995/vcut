package com.vcut.api.job.infrastructure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vcut.api.job.application.OutboxRepository;
import com.vcut.api.job.domain.OutboxMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class OutboxPublisherTest {

  private final OutboxRepository repository = mock(OutboxRepository.class);
  private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
  private final OutboxPublisher publisher = new OutboxPublisher(repository, rabbitTemplate);

  @Test
  void marksOutboxPublishedOnlyAfterBrokerConfirmation() {
    OutboxMessage message = message();
    when(repository.findPending(anyInt(), any(Instant.class))).thenReturn(List.of(message));
    doAnswer(
            invocation -> {
              CorrelationData correlation = invocation.getArgument(3);
              correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
              return null;
            })
        .when(rabbitTemplate)
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

    publisher.publishPending();

    verify(repository).markPublished(eq(message.id()), any(Instant.class));
    verify(rabbitTemplate)
        .send(
            eq(JobMessagingConfiguration.COMMAND_EXCHANGE),
            eq("pipeline.video.validate"),
            any(Message.class),
            any(CorrelationData.class));
  }

  @Test
  void keepsOutboxPendingAndSchedulesBackoffWhenBrokerIsUnavailable() {
    OutboxMessage message = message();
    when(repository.findPending(anyInt(), any(Instant.class))).thenReturn(List.of(message));
    doThrow(new IllegalStateException("broker unavailable"))
        .when(rabbitTemplate)
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

    publisher.publishPending();

    verify(repository)
        .markAttempt(eq(message.id()), eq(1), any(Instant.class), eq("broker unavailable"));
  }

  @Test
  void forwardsPlanWorkerPriorityToRabbitMessageProperties() {
    OutboxMessage message = message("{\"data\":{\"workerPriority\":7},\"eventType\":\"Process\"}");
    when(repository.findPending(anyInt(), any(Instant.class))).thenReturn(List.of(message));
    doAnswer(
            invocation -> {
              CorrelationData correlation = invocation.getArgument(3);
              correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
              return null;
            })
        .when(rabbitTemplate)
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    OutboxPublisher priorityPublisher =
        new OutboxPublisher(repository, rabbitTemplate, new ObjectMapper(), 10);

    priorityPublisher.publishPending();

    org.mockito.ArgumentCaptor<Message> messageCaptor =
        org.mockito.ArgumentCaptor.forClass(Message.class);
    org.mockito.ArgumentCaptor<String> routingKeyCaptor =
        org.mockito.ArgumentCaptor.forClass(String.class);
    verify(rabbitTemplate)
        .send(
            anyString(),
            routingKeyCaptor.capture(),
            messageCaptor.capture(),
            any(CorrelationData.class));
    MessageProperties properties = messageCaptor.getValue().getMessageProperties();
    org.assertj.core.api.Assertions.assertThat(properties.getPriority()).isEqualTo(7);
    assertThat(routingKeyCaptor.getValue()).isEqualTo("pipeline.video.validate.premium");
  }

  private static OutboxMessage message() {
    return message("{}");
  }

  private static OutboxMessage message(String payload) {
    Instant now = Instant.parse("2026-09-30T12:00:00Z");
    return new OutboxMessage(
        UUID.randomUUID(),
        "JOB",
        UUID.randomUUID(),
        "VideoValidationRequested",
        "pipeline.video.validate",
        payload,
        0,
        now,
        null,
        null,
        now);
  }
}
