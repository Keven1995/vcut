package com.vcut.api.transcription.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vcut.api.shared.correlation.CorrelationIdFilter;
import com.vcut.api.shared.errors.GlobalExceptionHandler;
import com.vcut.api.transcription.application.TranscriptionApplicationService;
import com.vcut.api.transcription.domain.Transcription;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TranscriptionControllerTest {

  private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID VIDEO_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

  private final TranscriptionApplicationService service =
      mock(TranscriptionApplicationService.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new TranscriptionController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();
  }

  @Test
  void getsTranscriptionUsingTheVideoPathVariable() throws Exception {
    when(service.get(USER_ID, VIDEO_ID)).thenReturn(transcription());

    mockMvc
        .perform(get("/api/videos/{videoId}/transcription", VIDEO_ID).principal(USER_ID::toString))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.videoId").value(VIDEO_ID.toString()))
        .andExpect(jsonPath("$.status").value("QUEUED"));
  }

  @Test
  void queuesTranscriptionUsingTheVideoPathVariable() throws Exception {
    when(service.request(USER_ID, VIDEO_ID, null)).thenReturn(transcription());

    mockMvc
        .perform(
            post("/api/videos/{videoId}/transcription", VIDEO_ID)
                .principal(USER_ID::toString)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.videoId").value(VIDEO_ID.toString()))
        .andExpect(jsonPath("$.status").value("QUEUED"));
  }

  private static Transcription transcription() {
    return Transcription.queued(UUID.randomUUID(), VIDEO_ID, USER_ID, 1, "configured", "und", NOW);
  }
}
