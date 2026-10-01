package com.vcut.api.job.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vcut.api.job.application.JobApplicationService;
import com.vcut.api.job.domain.Job;
import com.vcut.api.job.domain.Pipeline;
import com.vcut.api.shared.correlation.CorrelationIdFilter;
import com.vcut.api.shared.errors.GlobalExceptionHandler;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class JobControllerTest {

  private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID VIDEO_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID JOB_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

  private final JobApplicationService service = mock(JobApplicationService.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new JobController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();
  }

  @Test
  void acceptsProcessingAsynchronously() throws Exception {
    Job job = job();
    when(service.process(USER_ID, VIDEO_ID)).thenReturn(job);

    mockMvc
        .perform(post("/api/videos/{videoId}/process", VIDEO_ID).with(principal()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value(JOB_ID.toString()))
        .andExpect(jsonPath("$.status").value("QUEUED"))
        .andExpect(jsonPath("$.stage").value("INGEST"));
  }

  @Test
  void returnsOnlyTheAuthenticatedUsersJob() throws Exception {
    when(service.get(USER_ID, JOB_ID)).thenReturn(job());

    mockMvc
        .perform(get("/api/jobs/{jobId}", JOB_ID).with(principal()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.videoId").value(VIDEO_ID.toString()))
        .andExpect(jsonPath("$.progress").value(0.0));
  }

  @Test
  void rejectsProcessingWithoutAnAuthenticatedPrincipal() throws Exception {
    mockMvc
        .perform(post("/api/videos/{videoId}/process", VIDEO_ID))
        .andExpect(status().isUnauthorized());
  }

  private static Job job() {
    UUID projectId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    UUID correlationId = UUID.fromString("44444444-4444-4444-8444-444444444444");
    Pipeline pipeline =
        Pipeline.queued(UUID.randomUUID(), USER_ID, projectId, VIDEO_ID, 1, correlationId, NOW);
    return Job.queued(
        JOB_ID,
        pipeline,
        JobApplicationService.OPERATION,
        JobApplicationService.idempotencyKey(VIDEO_ID, 1),
        JobApplicationService.STAGE,
        NOW);
  }

  private static RequestPostProcessor principal() {
    return request -> {
      request.setUserPrincipal(() -> USER_ID.toString());
      return request;
    };
  }
}
