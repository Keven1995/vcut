package com.vcut.api.job.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.Declarables;
import org.junit.jupiter.api.Test;

class JobMessagingConfigurationTest {

  @Test
  void commandAndRetryQueuesSupportConfiguredPlanPriorities() {
    JobMessagingConfiguration configuration = new JobMessagingConfiguration();

    assertThat(configuration.commandQueue(10).getArguments()).containsEntry("x-max-priority", 10);
    assertThat(configuration.transcriptionCommandQueue(10).getArguments())
        .containsEntry("x-max-priority", 10);
    assertThat(configuration.retryQueue(10).getArguments()).containsEntry("x-max-priority", 10);
  }

  @Test
  void rejectsQueuePriorityRangesOutsideTheWorkerContract() {
    JobMessagingConfiguration configuration = new JobMessagingConfiguration();

    assertThatThrownBy(() -> configuration.commandQueue(11))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("between 1 and 10");
  }

  @Test
  void declaresPremiumCommandAndRetryLanesForEveryOperation() {
    Declarables declarations = new JobMessagingConfiguration().premiumPlanLaneTopology(10);
    List<String> queueNames =
        declarations.getDeclarables().stream()
            .filter(Queue.class::isInstance)
            .map(Queue.class::cast)
            .map(Queue::getName)
            .toList();
    List<String> routingKeys =
        declarations.getDeclarables().stream()
            .filter(Binding.class::isInstance)
            .map(Binding.class::cast)
            .map(Binding::getRoutingKey)
            .toList();

    assertThat(queueNames)
        .contains(
            JobMessagingConfiguration.COMMAND_QUEUE + ".premium",
            JobMessagingConfiguration.TRANSCRIPTION_COMMAND_QUEUE + ".premium",
            JobMessagingConfiguration.CLIP_ANALYSIS_COMMAND_QUEUE + ".premium",
            JobMessagingConfiguration.CLIP_GENERATION_COMMAND_QUEUE + ".premium",
            JobMessagingConfiguration.FINAL_RENDER_COMMAND_QUEUE + ".premium",
            JobMessagingConfiguration.RETRY_QUEUE + ".premium",
            JobMessagingConfiguration.TRANSCRIPTION_RETRY_QUEUE + ".premium",
            JobMessagingConfiguration.CLIP_ANALYSIS_RETRY_QUEUE + ".premium",
            JobMessagingConfiguration.CLIP_GENERATION_RETRY_QUEUE + ".premium",
            JobMessagingConfiguration.FINAL_RENDER_RETRY_QUEUE + ".premium");
    assertThat(routingKeys)
        .contains(
            JobMessagingConfiguration.COMMAND_ROUTING_KEY + ".premium",
            JobMessagingConfiguration.TRANSCRIPTION_COMMAND_ROUTING_KEY + ".premium",
            JobMessagingConfiguration.CLIP_ANALYSIS_COMMAND_ROUTING_KEY + ".premium",
            JobMessagingConfiguration.CLIP_GENERATION_COMMAND_ROUTING_KEY + ".premium",
            JobMessagingConfiguration.FINAL_RENDER_COMMAND_ROUTING_KEY + ".premium");
    Queue premiumVideoValidationQueue =
        declarations.getDeclarables().stream()
            .filter(Queue.class::isInstance)
            .map(Queue.class::cast)
            .filter(queue -> queue.getName().equals(JobMessagingConfiguration.COMMAND_QUEUE + ".premium"))
            .findFirst()
            .orElseThrow();
    assertThat(premiumVideoValidationQueue.isDurable()).isTrue();
    assertThat(premiumVideoValidationQueue.getArguments())
        .containsEntry("x-dead-letter-routing-key", "pipeline.video.validate.premium");
  }
}
