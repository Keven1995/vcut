package com.vcut.api.job.infrastructure;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.Channel;
import com.vcut.api.clip.application.ClipAnalysisApplicationService;
import com.vcut.api.job.application.JobApplicationService;
import com.vcut.api.shared.messaging.MessageEnvelope;
import com.vcut.api.shared.messaging.MessageKind;
import com.vcut.api.transcription.application.TranscriptionApplicationService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;

class WorkerResultListenerTest {

  @Test
  void routesClipAnalysisResultToTheClipService() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    JobApplicationService jobService = mock(JobApplicationService.class);
    TranscriptionApplicationService transcriptionService =
        mock(TranscriptionApplicationService.class);
    ClipAnalysisApplicationService clipService = mock(ClipAnalysisApplicationService.class);
    WorkerResultListener listener =
        new WorkerResultListener(objectMapper, jobService, transcriptionService, clipService);
    MessageEnvelope envelope =
        new MessageEnvelope(
            MessageKind.EVENT,
            UUID.randomUUID(),
            ClipAnalysisApplicationService.RESULT_EVENT_TYPE,
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            ClipAnalysisApplicationService.OPERATION,
            1,
            UUID.randomUUID(),
            1,
            Instant.parse("2026-09-30T12:00:00Z"),
            Map.of("status", "FAILED", "candidates", List.of()));
    Channel channel = mock(Channel.class);

    listener.receive(new Message(objectMapper.writeValueAsBytes(envelope)), channel, 10L);

    verify(clipService).handleResult(envelope);
    verify(channel).basicAck(10L, false);
  }
}
