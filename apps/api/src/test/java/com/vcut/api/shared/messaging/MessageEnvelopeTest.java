package com.vcut.api.shared.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageEnvelopeTest {

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void deserializesTheSharedFixtureAndIgnoresOptionalFields() throws IOException {
    String json = Files.readString(fixturePath());

    MessageEnvelope message = objectMapper.readValue(json, MessageEnvelope.class);

    assertThat(message.eventType()).isEqualTo("VideoUploaded");
    assertThat(message.data()).containsEntry("optionalLabel", "source");
    MessageCompatibility.requireSupported(message);
  }

  @Test
  void serializesTheRequiredEnvelopeFields() throws IOException {
    MessageEnvelope message =
        new MessageEnvelope(
            MessageKind.COMMAND,
            UUID.randomUUID(),
            "ProcessVideo",
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "video.process",
            1,
            UUID.randomUUID(),
            1,
            java.time.Instant.parse("2026-09-28T12:00:00Z"),
            Map.of("requestedBy", "test"));

    Map<String, Object> serialized =
        objectMapper.readValue(objectMapper.writeValueAsString(message), new TypeReference<>() {});

    assertThat(serialized)
        .containsKeys(
            "kind",
            "eventId",
            "eventType",
            "eventVersion",
            "jobId",
            "resourceId",
            "operation",
            "version",
            "correlationId",
            "attempt",
            "occurredAt",
            "data");
  }

  @Test
  void rejectsUnsupportedVersions() {
    MessageEnvelope message =
        new MessageEnvelope(
            MessageKind.EVENT,
            UUID.randomUUID(),
            "VideoUploaded",
            2,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "video.ingest",
            1,
            UUID.randomUUID(),
            1,
            java.time.Instant.now(),
            Map.of());

    assertThatThrownBy(() -> MessageCompatibility.requireSupported(message))
        .isInstanceOf(MessageCompatibility.UnsupportedMessageVersionException.class);
  }

  @Test
  void roundTripsOptionalW3cTraceparentWithoutChangingTheCorrelationId() throws IOException {
    UUID correlationId = UUID.randomUUID();
    String traceparent = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01";
    MessageEnvelope message =
        new MessageEnvelope(
            MessageKind.COMMAND,
            UUID.randomUUID(),
            "ProcessVideo",
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "video.process",
            1,
            correlationId,
            traceparent,
            1,
            java.time.Instant.now(),
            Map.of());

    MessageEnvelope roundTrip =
        objectMapper.readValue(objectMapper.writeValueAsString(message), MessageEnvelope.class);

    assertThat(roundTrip.correlationId()).isEqualTo(correlationId);
    assertThat(roundTrip.traceparent()).isEqualTo(traceparent);
  }

  private static Path fixturePath() {
    Path rootPath = Path.of("tests/fixtures/events/video-uploaded-v1.json");
    return Files.exists(rootPath)
        ? rootPath
        : Path.of("..", "..", "tests", "fixtures", "events", "video-uploaded-v1.json").normalize();
  }
}
