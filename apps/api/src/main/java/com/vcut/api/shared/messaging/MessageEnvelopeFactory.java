package com.vcut.api.shared.messaging;

import com.vcut.api.shared.correlation.CorrelationContext;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class MessageEnvelopeFactory {

  private MessageEnvelopeFactory() {}

  public static MessageEnvelope create(
      MessageKind kind,
      UUID eventId,
      String eventType,
      int eventVersion,
      UUID jobId,
      UUID resourceId,
      String operation,
      int version,
      int attempt,
      Map<String, Object> data) {
    return new MessageEnvelope(
        kind,
        eventId,
        eventType,
        eventVersion,
        jobId,
        resourceId,
        operation,
        version,
        CorrelationContext.require(),
        attempt,
        Instant.now(),
        data);
  }
}
