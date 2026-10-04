package com.vcut.api.job.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
