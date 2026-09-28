package com.vcut.api.shared.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.vcut.api.shared.correlation.CorrelationContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MessageEnvelopeFactoryTest {

  @AfterEach
  void clearCorrelationContext() {
    CorrelationContext.clear();
  }

  @Test
  void propagatesTheRequestCorrelationIdToTheMessage() {
    UUID correlationId = UUID.randomUUID();
    CorrelationContext.set(correlationId);

    MessageEnvelope message =
        MessageEnvelopeFactory.create(
            MessageKind.COMMAND,
            UUID.randomUUID(),
            "ProcessVideo",
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "video.process",
            1,
            1,
            Map.of());

    assertThat(message.correlationId()).isEqualTo(correlationId);
  }
}
