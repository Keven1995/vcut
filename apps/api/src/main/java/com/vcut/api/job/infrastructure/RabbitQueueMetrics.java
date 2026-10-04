package com.vcut.api.job.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "vcut.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitQueueMetrics implements MeterBinder {

  private static final Logger LOGGER = LoggerFactory.getLogger(RabbitQueueMetrics.class);
  private static final Map<String, String> QUEUES =
      Map.ofEntries(
          Map.entry("command.video-validation", JobMessagingConfiguration.COMMAND_QUEUE),
          Map.entry("command.transcription", JobMessagingConfiguration.TRANSCRIPTION_COMMAND_QUEUE),
          Map.entry("command.clip-analysis", JobMessagingConfiguration.CLIP_ANALYSIS_COMMAND_QUEUE),
          Map.entry(
              "command.clip-generation", JobMessagingConfiguration.CLIP_GENERATION_COMMAND_QUEUE),
          Map.entry("command.final-render", JobMessagingConfiguration.FINAL_RENDER_COMMAND_QUEUE),
          Map.entry("retry.video-validation", JobMessagingConfiguration.RETRY_QUEUE),
          Map.entry("retry.transcription", JobMessagingConfiguration.TRANSCRIPTION_RETRY_QUEUE),
          Map.entry("retry.clip-analysis", JobMessagingConfiguration.CLIP_ANALYSIS_RETRY_QUEUE),
          Map.entry("retry.clip-generation", JobMessagingConfiguration.CLIP_GENERATION_RETRY_QUEUE),
          Map.entry("retry.final-render", JobMessagingConfiguration.FINAL_RENDER_RETRY_QUEUE),
          Map.entry("dlq.video-validation", JobMessagingConfiguration.DEAD_LETTER_QUEUE),
          Map.entry("dlq.transcription", JobMessagingConfiguration.TRANSCRIPTION_DEAD_LETTER_QUEUE),
          Map.entry("dlq.clip-analysis", JobMessagingConfiguration.CLIP_ANALYSIS_DEAD_LETTER_QUEUE),
          Map.entry(
              "dlq.clip-generation", JobMessagingConfiguration.CLIP_GENERATION_DEAD_LETTER_QUEUE),
          Map.entry("dlq.final-render", JobMessagingConfiguration.FINAL_RENDER_DEAD_LETTER_QUEUE),
          Map.entry("result.api", JobMessagingConfiguration.RESULT_QUEUE));

  private final RabbitAdmin rabbitAdmin;
  private final Map<String, QueueStats> queueStats =
      QUEUES.keySet().stream()
          .collect(
              java.util.stream.Collectors.toUnmodifiableMap(
                  name -> name, ignored -> new QueueStats()));
  private final AtomicBoolean available = new AtomicBoolean();

  public RabbitQueueMetrics(RabbitAdmin rabbitAdmin) {
    this.rabbitAdmin = rabbitAdmin;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("vcut.rabbitmq.connection.available", available, value -> value.get() ? 1 : 0)
        .register(registry);
    QUEUES.forEach(
        (name, ignored) -> {
          QueueStats stats = queueStats.get(name);
          Gauge.builder("vcut.rabbitmq.queue.messages", stats, item -> item.messages.get())
              .tag("queue", name)
              .register(registry);
          Gauge.builder("vcut.rabbitmq.queue.consumers", stats, item -> item.consumers.get())
              .tag("queue", name)
              .register(registry);
        });
  }

  @Scheduled(fixedDelayString = "${vcut.messaging.metrics-poll-interval-ms:15000}")
  public void refresh() {
    try {
      for (Map.Entry<String, String> queue : QUEUES.entrySet()) {
        QueueInformation information = rabbitAdmin.getQueueInfo(queue.getValue());
        QueueStats stats = queueStats.get(queue.getKey());
        stats.messages.set(information == null ? -1 : information.getMessageCount());
        stats.consumers.set(information == null ? -1 : information.getConsumerCount());
      }
      available.set(true);
    } catch (RuntimeException failure) {
      available.set(false);
      queueStats
          .values()
          .forEach(
              stats -> {
                stats.messages.set(-1);
                stats.consumers.set(-1);
              });
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug(
            "rabbitmq_metrics_unavailable errorType={}", failure.getClass().getSimpleName());
      }
    }
  }

  private static final class QueueStats {
    private final AtomicLong messages = new AtomicLong(-1);
    private final AtomicLong consumers = new AtomicLong(-1);
  }
}
