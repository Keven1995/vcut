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

import com.vcut.api.job.application.OutboxRepository;
import com.vcut.api.job.domain.OutboxMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
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

  private static OutboxMessage message() {
    Instant now = Instant.parse("2026-09-30T12:00:00Z");
    return new OutboxMessage(
        UUID.randomUUID(),
        "JOB",
        UUID.randomUUID(),
        "VideoValidationRequested",
        "pipeline.video.validate",
        "{}",
        0,
        now,
        null,
        null,
        now);
  }
}
