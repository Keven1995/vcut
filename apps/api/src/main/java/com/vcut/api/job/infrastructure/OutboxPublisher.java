package com.vcut.api.job.infrastructure;

import com.vcut.api.job.application.OutboxRepository;
import com.vcut.api.job.domain.OutboxMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "vcut.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

  private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisher.class);

  private final OutboxRepository outboxRepository;
  private final RabbitTemplate rabbitTemplate;

  public OutboxPublisher(OutboxRepository outboxRepository, RabbitTemplate rabbitTemplate) {
    this.outboxRepository = outboxRepository;
    this.rabbitTemplate = rabbitTemplate;
  }

  @Scheduled(fixedDelayString = "${vcut.messaging.publisher-delay-ms:1000}")
  public void publishPending() {
    Instant now = Instant.now();
    for (OutboxMessage message : outboxRepository.findPending(50, now)) {
      publish(message, now);
    }
  }

  private void publish(OutboxMessage message, Instant now) {
    int attempt = message.attempt() + 1;
    try {
      MessageProperties properties = new MessageProperties();
      properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
      properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
      CorrelationData correlation = new CorrelationData(message.id().toString());
      rabbitTemplate.send(
          JobMessagingConfiguration.COMMAND_EXCHANGE,
          message.routingKey(),
          new Message(message.payload().getBytes(StandardCharsets.UTF_8), properties),
          correlation);
      CorrelationData.Confirm confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
      if (!confirm.isAck()) {
        throw new IllegalStateException("RabbitMQ rejected the outbox message.");
      }
      outboxRepository.markPublished(message.id(), Instant.now());
    } catch (Exception exception) {
      outboxRepository.markAttempt(
          message.id(), attempt, now.plusSeconds(backoffSeconds(attempt)), safeMessage(exception));
      LOGGER.warn(
          "outbox_publish_failed messageId={} attempt={}", message.id(), attempt, exception);
    }
  }

  private static long backoffSeconds(int attempt) {
    return Math.min(120, 5L * (1L << Math.min(attempt - 1, 4)));
  }

  private static String safeMessage(Exception exception) {
    String message = exception.getMessage();
    return (message == null || message.isBlank() ? exception.getClass().getSimpleName() : message)
        .substring(
            0,
            Math.min(
                1_000,
                message == null
                    ? exception.getClass().getSimpleName().length()
                    : message.length()));
  }
}
