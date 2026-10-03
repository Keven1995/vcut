package com.vcut.api.job.infrastructure;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "vcut.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class JobMessagingConfiguration {

  public static final String COMMAND_EXCHANGE = "vcut.pipeline.commands";
  public static final String RETRY_EXCHANGE = "vcut.pipeline.retry";
  public static final String DEAD_LETTER_EXCHANGE = "vcut.pipeline.dlx";
  public static final String RESULT_EXCHANGE = "vcut.pipeline.results";
  public static final String COMMAND_QUEUE = "vcut.pipeline.commands.video-validation";
  public static final String TRANSCRIPTION_COMMAND_QUEUE = "vcut.pipeline.commands.transcription";
  public static final String CLIP_ANALYSIS_COMMAND_QUEUE = "vcut.pipeline.commands.clip-analysis";
  public static final String CLIP_GENERATION_COMMAND_QUEUE =
      "vcut.pipeline.commands.clip-generation";
  public static final String RETRY_QUEUE = "vcut.pipeline.retry.video-validation";
  public static final String TRANSCRIPTION_RETRY_QUEUE = "vcut.pipeline.retry.transcription";
  public static final String CLIP_ANALYSIS_RETRY_QUEUE = "vcut.pipeline.retry.clip-analysis";
  public static final String CLIP_GENERATION_RETRY_QUEUE = "vcut.pipeline.retry.clip-generation";
  public static final String DEAD_LETTER_QUEUE = "vcut.pipeline.dlq.video-validation";
  public static final String TRANSCRIPTION_DEAD_LETTER_QUEUE = "vcut.pipeline.dlq.transcription";
  public static final String CLIP_ANALYSIS_DEAD_LETTER_QUEUE = "vcut.pipeline.dlq.clip-analysis";
  public static final String CLIP_GENERATION_DEAD_LETTER_QUEUE =
      "vcut.pipeline.dlq.clip-generation";
  public static final String RESULT_QUEUE = "vcut.pipeline.results.api";
  public static final String COMMAND_ROUTING_KEY = "pipeline.video.validate";
  public static final String TRANSCRIPTION_COMMAND_ROUTING_KEY = "pipeline.video.transcribe";
  public static final String CLIP_ANALYSIS_COMMAND_ROUTING_KEY = "pipeline.video.analyze-clips";
  public static final String CLIP_GENERATION_COMMAND_ROUTING_KEY = "pipeline.video.generate-clip";
  public static final String RESULT_ROUTING_KEY = "pipeline.video.validation.completed";
  public static final String TRANSCRIPTION_RESULT_ROUTING_KEY =
      "pipeline.video.transcription.completed";
  public static final String CLIP_ANALYSIS_RESULT_ROUTING_KEY =
      "pipeline.video.clip-analysis.completed";
  public static final String CLIP_GENERATION_RESULT_ROUTING_KEY =
      "pipeline.video.clip-generation.completed";

  @Bean
  DirectExchange commandExchange() {
    return new DirectExchange(COMMAND_EXCHANGE, true, false);
  }

  @Bean
  DirectExchange retryExchange() {
    return new DirectExchange(RETRY_EXCHANGE, true, false);
  }

  @Bean
  DirectExchange deadLetterExchange() {
    return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
  }

  @Bean
  DirectExchange resultExchange() {
    return new DirectExchange(RESULT_EXCHANGE, true, false);
  }

  @Bean
  Queue commandQueue() {
    return QueueBuilder.durable(COMMAND_QUEUE)
        .deadLetterExchange(DEAD_LETTER_EXCHANGE)
        .deadLetterRoutingKey(COMMAND_ROUTING_KEY)
        .build();
  }

  @Bean
  Queue transcriptionCommandQueue() {
    return QueueBuilder.durable(TRANSCRIPTION_COMMAND_QUEUE)
        .deadLetterExchange(DEAD_LETTER_EXCHANGE)
        .deadLetterRoutingKey(TRANSCRIPTION_COMMAND_ROUTING_KEY)
        .build();
  }

  @Bean
  Queue clipAnalysisCommandQueue() {
    return QueueBuilder.durable(CLIP_ANALYSIS_COMMAND_QUEUE)
        .deadLetterExchange(DEAD_LETTER_EXCHANGE)
        .deadLetterRoutingKey(CLIP_ANALYSIS_COMMAND_ROUTING_KEY)
        .build();
  }

  @Bean
  Queue clipGenerationCommandQueue() {
    return QueueBuilder.durable(CLIP_GENERATION_COMMAND_QUEUE)
        .deadLetterExchange(DEAD_LETTER_EXCHANGE)
        .deadLetterRoutingKey(CLIP_GENERATION_COMMAND_ROUTING_KEY)
        .build();
  }

  @Bean
  Queue retryQueue() {
    return QueueBuilder.durable(RETRY_QUEUE)
        .ttl(300_000)
        .deadLetterExchange(COMMAND_EXCHANGE)
        .deadLetterRoutingKey(COMMAND_ROUTING_KEY)
        .build();
  }

  @Bean
  Queue transcriptionRetryQueue() {
    return QueueBuilder.durable(TRANSCRIPTION_RETRY_QUEUE)
        .ttl(300_000)
        .deadLetterExchange(COMMAND_EXCHANGE)
        .deadLetterRoutingKey(TRANSCRIPTION_COMMAND_ROUTING_KEY)
        .build();
  }

  @Bean
  Queue clipAnalysisRetryQueue() {
    return QueueBuilder.durable(CLIP_ANALYSIS_RETRY_QUEUE)
        .ttl(300_000)
        .deadLetterExchange(COMMAND_EXCHANGE)
        .deadLetterRoutingKey(CLIP_ANALYSIS_COMMAND_ROUTING_KEY)
        .build();
  }

  @Bean
  Queue clipGenerationRetryQueue() {
    return QueueBuilder.durable(CLIP_GENERATION_RETRY_QUEUE)
        .ttl(300_000)
        .deadLetterExchange(COMMAND_EXCHANGE)
        .deadLetterRoutingKey(CLIP_GENERATION_COMMAND_ROUTING_KEY)
        .build();
  }

  @Bean
  Queue deadLetterQueue() {
    return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
  }

  @Bean
  Queue transcriptionDeadLetterQueue() {
    return QueueBuilder.durable(TRANSCRIPTION_DEAD_LETTER_QUEUE).build();
  }

  @Bean
  Queue clipAnalysisDeadLetterQueue() {
    return QueueBuilder.durable(CLIP_ANALYSIS_DEAD_LETTER_QUEUE).build();
  }

  @Bean
  Queue clipGenerationDeadLetterQueue() {
    return QueueBuilder.durable(CLIP_GENERATION_DEAD_LETTER_QUEUE).build();
  }

  @Bean
  Queue resultQueue() {
    return QueueBuilder.durable(RESULT_QUEUE)
        .deadLetterExchange(DEAD_LETTER_EXCHANGE)
        .deadLetterRoutingKey(RESULT_ROUTING_KEY)
        .build();
  }

  @Bean
  Binding commandBinding(
      @Qualifier("commandQueue") Queue commandQueue,
      @Qualifier("commandExchange") DirectExchange commandExchange) {
    return BindingBuilder.bind(commandQueue).to(commandExchange).with(COMMAND_ROUTING_KEY);
  }

  @Bean
  Binding transcriptionCommandBinding(
      @Qualifier("transcriptionCommandQueue") Queue transcriptionCommandQueue,
      @Qualifier("commandExchange") DirectExchange commandExchange) {
    return BindingBuilder.bind(transcriptionCommandQueue)
        .to(commandExchange)
        .with(TRANSCRIPTION_COMMAND_ROUTING_KEY);
  }

  @Bean
  Binding clipAnalysisCommandBinding(
      @Qualifier("clipAnalysisCommandQueue") Queue clipAnalysisCommandQueue,
      @Qualifier("commandExchange") DirectExchange commandExchange) {
    return BindingBuilder.bind(clipAnalysisCommandQueue)
        .to(commandExchange)
        .with(CLIP_ANALYSIS_COMMAND_ROUTING_KEY);
  }

  @Bean
  Binding clipGenerationCommandBinding(
      @Qualifier("clipGenerationCommandQueue") Queue clipGenerationCommandQueue,
      @Qualifier("commandExchange") DirectExchange commandExchange) {
    return BindingBuilder.bind(clipGenerationCommandQueue)
        .to(commandExchange)
        .with(CLIP_GENERATION_COMMAND_ROUTING_KEY);
  }

  @Bean
  Binding retryBinding(
      @Qualifier("retryQueue") Queue retryQueue,
      @Qualifier("retryExchange") DirectExchange retryExchange) {
    return BindingBuilder.bind(retryQueue).to(retryExchange).with(COMMAND_ROUTING_KEY);
  }

  @Bean
  Binding transcriptionRetryBinding(
      @Qualifier("transcriptionRetryQueue") Queue transcriptionRetryQueue,
      @Qualifier("retryExchange") DirectExchange retryExchange) {
    return BindingBuilder.bind(transcriptionRetryQueue)
        .to(retryExchange)
        .with(TRANSCRIPTION_COMMAND_ROUTING_KEY);
  }

  @Bean
  Binding clipAnalysisRetryBinding(
      @Qualifier("clipAnalysisRetryQueue") Queue clipAnalysisRetryQueue,
      @Qualifier("retryExchange") DirectExchange retryExchange) {
    return BindingBuilder.bind(clipAnalysisRetryQueue)
        .to(retryExchange)
        .with(CLIP_ANALYSIS_COMMAND_ROUTING_KEY);
  }

  @Bean
  Binding clipGenerationRetryBinding(
      @Qualifier("clipGenerationRetryQueue") Queue clipGenerationRetryQueue,
      @Qualifier("retryExchange") DirectExchange retryExchange) {
    return BindingBuilder.bind(clipGenerationRetryQueue)
        .to(retryExchange)
        .with(CLIP_GENERATION_COMMAND_ROUTING_KEY);
  }

  @Bean
  Binding deadLetterBinding(
      @Qualifier("deadLetterQueue") Queue deadLetterQueue,
      @Qualifier("deadLetterExchange") DirectExchange deadLetterExchange) {
    return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(COMMAND_ROUTING_KEY);
  }

  @Bean
  Binding transcriptionDeadLetterBinding(
      @Qualifier("transcriptionDeadLetterQueue") Queue transcriptionDeadLetterQueue,
      @Qualifier("deadLetterExchange") DirectExchange deadLetterExchange) {
    return BindingBuilder.bind(transcriptionDeadLetterQueue)
        .to(deadLetterExchange)
        .with(TRANSCRIPTION_COMMAND_ROUTING_KEY);
  }

  @Bean
  Binding clipAnalysisDeadLetterBinding(
      @Qualifier("clipAnalysisDeadLetterQueue") Queue clipAnalysisDeadLetterQueue,
      @Qualifier("deadLetterExchange") DirectExchange deadLetterExchange) {
    return BindingBuilder.bind(clipAnalysisDeadLetterQueue)
        .to(deadLetterExchange)
        .with(CLIP_ANALYSIS_COMMAND_ROUTING_KEY);
  }

  @Bean
  Binding clipGenerationDeadLetterBinding(
      @Qualifier("clipGenerationDeadLetterQueue") Queue clipGenerationDeadLetterQueue,
      @Qualifier("deadLetterExchange") DirectExchange deadLetterExchange) {
    return BindingBuilder.bind(clipGenerationDeadLetterQueue)
        .to(deadLetterExchange)
        .with(CLIP_GENERATION_COMMAND_ROUTING_KEY);
  }

  @Bean
  Binding deadLetterResultBinding(
      @Qualifier("deadLetterQueue") Queue deadLetterQueue,
      @Qualifier("deadLetterExchange") DirectExchange deadLetterExchange) {
    return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(RESULT_ROUTING_KEY);
  }

  @Bean
  Binding deadLetterTranscriptionResultBinding(
      @Qualifier("deadLetterQueue") Queue deadLetterQueue,
      @Qualifier("deadLetterExchange") DirectExchange deadLetterExchange) {
    return BindingBuilder.bind(deadLetterQueue)
        .to(deadLetterExchange)
        .with(TRANSCRIPTION_RESULT_ROUTING_KEY);
  }

  @Bean
  Binding deadLetterClipAnalysisResultBinding(
      @Qualifier("clipAnalysisDeadLetterQueue") Queue clipAnalysisDeadLetterQueue,
      @Qualifier("deadLetterExchange") DirectExchange deadLetterExchange) {
    return BindingBuilder.bind(clipAnalysisDeadLetterQueue)
        .to(deadLetterExchange)
        .with(CLIP_ANALYSIS_RESULT_ROUTING_KEY);
  }

  @Bean
  Binding deadLetterClipGenerationResultBinding(
      @Qualifier("clipGenerationDeadLetterQueue") Queue clipGenerationDeadLetterQueue,
      @Qualifier("deadLetterExchange") DirectExchange deadLetterExchange) {
    return BindingBuilder.bind(clipGenerationDeadLetterQueue)
        .to(deadLetterExchange)
        .with(CLIP_GENERATION_RESULT_ROUTING_KEY);
  }

  @Bean
  Binding resultBinding(
      @Qualifier("resultQueue") Queue resultQueue,
      @Qualifier("resultExchange") DirectExchange resultExchange) {
    return BindingBuilder.bind(resultQueue).to(resultExchange).with(RESULT_ROUTING_KEY);
  }

  @Bean
  Binding transcriptionResultBinding(
      @Qualifier("resultQueue") Queue resultQueue,
      @Qualifier("resultExchange") DirectExchange resultExchange) {
    return BindingBuilder.bind(resultQueue)
        .to(resultExchange)
        .with(TRANSCRIPTION_RESULT_ROUTING_KEY);
  }

  @Bean
  Binding clipAnalysisResultBinding(
      @Qualifier("resultQueue") Queue resultQueue,
      @Qualifier("resultExchange") DirectExchange resultExchange) {
    return BindingBuilder.bind(resultQueue)
        .to(resultExchange)
        .with(CLIP_ANALYSIS_RESULT_ROUTING_KEY);
  }

  @Bean
  Binding clipGenerationResultBinding(
      @Qualifier("resultQueue") Queue resultQueue,
      @Qualifier("resultExchange") DirectExchange resultExchange) {
    return BindingBuilder.bind(resultQueue)
        .to(resultExchange)
        .with(CLIP_GENERATION_RESULT_ROUTING_KEY);
  }

  @Bean
  Jackson2JsonMessageConverter rabbitMessageConverter() {
    return new Jackson2JsonMessageConverter();
  }

  @Bean
  RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMandatory(true);
    return template;
  }

  @Bean
  RabbitListenerContainerFactory<?> rabbitListenerContainerFactory(
      ConnectionFactory connectionFactory) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
    factory.setDefaultRequeueRejected(false);
    return factory;
  }
}
