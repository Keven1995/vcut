package com.vcut.api.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vcut.api.auth.infrastructure.JwtTokenService;
import com.vcut.api.video.application.ObjectStorage;
import com.vcut.api.video.infrastructure.S3CompatibleObjectStorageAdapter;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiLoadProfileTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(ApiLoadProfileTest.class);
  private static final int SAMPLE_COUNT = 12;
  private static final int THREAD_COUNT = 6;

  @Container
  static final GenericContainer<?> POSTGRES =
      new GenericContainer<>("postgres:16.14-alpine3.22")
          .withEnv("POSTGRES_USER", "load-test")
          .withEnv("POSTGRES_PASSWORD", "load-test-password")
          .withEnv("POSTGRES_DB", "load-test")
          .withExposedPorts(5432)
          .waitingFor(Wait.forListeningPort());

  @DynamicPropertySource
  static void configurePostgres(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () ->
            "jdbc:postgresql://"
                + POSTGRES.getHost()
                + ":"
                + POSTGRES.getMappedPort(5432)
                + "/load-test");
    registry.add("spring.datasource.username", () -> "load-test");
    registry.add("spring.datasource.password", () -> "load-test-password");
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("vcut.messaging.enabled", () -> false);
    registry.add("vcut.storage.enabled", () -> true);
    registry.add("vcut.storage.access-key", () -> "load-test-access-key");
    registry.add("vcut.storage.secret-key", () -> "load-test-secret-key");
    registry.add("OBJECT_STORAGE_ACCESS_KEY", () -> "load-test-access-key");
    registry.add("OBJECT_STORAGE_SECRET_KEY", () -> "load-test-secret-key");
    registry.add("vcut.auth.jwt-secret", () -> "00000000000000000000000000000000");
    registry.add("vcut.usage.free.max-concurrent-jobs", () -> 100);
    registry.add("vcut.usage.free.monthly-processing-minutes", () -> 120);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private JwtTokenService jwtTokenService;
  @Autowired private ObjectMapper objectMapper;
  @MockitoBean private S3CompatibleObjectStorageAdapter objectStorage;

  private UUID userId;
  private UUID projectId;
  private String accessToken;

  @BeforeEach
  void setUp() throws Exception {
    userId = UUID.randomUUID();
    projectId = UUID.randomUUID();
    String email = "load-" + userId + "@example.test";
    Timestamp now = Timestamp.from(Instant.now());
    jdbcTemplate.update(
        "INSERT INTO users (id, email, normalized_email, password_hash, status, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
        userId,
        email,
        email,
        "load-test-hash",
        now,
        now);
    jdbcTemplate.update(
        "INSERT INTO projects (id, user_id, name, status, created_at, updated_at) "
            + "VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
        projectId,
        userId,
        "Load profile " + userId,
        now,
        now);
    accessToken = jwtTokenService.issue(userId, email).value();
    when(objectStorage.presignUpload(anyString(), anyString(), anyLong()))
        .thenAnswer(
            invocation ->
                new ObjectStorage.PresignedUpload(
                    "https://storage.example.test/upload",
                    Instant.now().plusSeconds(900)));
    when(objectStorage.head(anyString()))
        .thenAnswer(
            invocation ->
                java.util.Optional.of(
                    new ObjectStorage.StoredObject(
                        invocation.getArgument(0), 2_048, "video/mp4", "load-etag", null)));
  }

  @Test
  void measuresUploadJobQueryAndRenderApiUnderConcurrentLoad() throws Exception {
    List<RenderFixture> renderFixtures = new ArrayList<>(SAMPLE_COUNT);
    for (int index = 0; index < SAMPLE_COUNT; index++) {
      renderFixtures.add(seedRenderFixture());
    }
    seedHistoricalCompletedJobs(renderFixtures.getFirst().videoId(), 20_000);
    seedHistoricalCompletedDomainRows(renderFixtures.getFirst(), 5_000);
    long databaseBytesBefore = databaseBytes();
    long databaseTuplesBefore = databaseTuples();
    List<UUID> videoIds = java.util.Collections.synchronizedList(new ArrayList<>());

    List<Double> uploadConfirmLatency =
        runConcurrent(
            SAMPLE_COUNT,
            index -> {
              MvcResult created =
                  mockMvc
                      .perform(
                          authorized(
                                  post("/api/projects/{projectId}/videos", projectId))
                              .contentType(MediaType.APPLICATION_JSON)
                              .content(
                                  objectMapper.writeValueAsString(
                                      Map.of(
                                          "filename", "load-" + index + ".mp4",
                                          "contentType", "video/mp4",
                                          "sizeBytes", 2_048))))
                      .andExpect(status().isCreated())
                      .andReturn();
              UUID videoId = UUID.fromString(json(created).path("id").asText());
              mockMvc
                  .perform(
                      authorized(post("/api/videos/{videoId}/confirm", videoId))
                          .contentType(MediaType.APPLICATION_JSON)
                          .content("{}"))
                  .andExpect(status().isOk());
              videoIds.add(videoId);
            });

    List<UUID> jobIds = java.util.Collections.synchronizedList(new ArrayList<>());
    List<Double> jobCreateLatency =
        runConcurrent(
            videoIds.size(),
            index -> {
              MvcResult accepted =
                  mockMvc
                      .perform(
                          authorized(post("/api/videos/{videoId}/process", videoIds.get(index))))
                      .andExpect(status().isAccepted())
                      .andReturn();
              jobIds.add(UUID.fromString(json(accepted).path("id").asText()));
            });
    String activeWorkQueryPlan = explainActiveWorkQuery();
    String clipPageQueryPlan = explainClipPageQuery(renderFixtures.getFirst(), 250, 10);

    List<Double> jobQueryLatency =
        runConcurrent(
            jobIds.size() * 3,
            index -> {
              UUID jobId = jobIds.get(index % jobIds.size());
              mockMvc
                  .perform(authorized(get("/api/jobs/{jobId}", jobId)))
                  .andExpect(status().isOk());
            });

    List<Double> clipPageLatency =
        runConcurrent(
            SAMPLE_COUNT,
            index -> {
              MvcResult page =
                  mockMvc
                      .perform(
                          authorized(
                                  get(
                                      "/api/videos/{videoId}/clips",
                                      renderFixtures.getFirst().videoId()))
                              .param("page", "250")
                              .param("size", "10"))
                      .andExpect(status().isOk())
                      .andReturn();
              assertThat(json(page).path("content").size()).isEqualTo(10);
            });

    List<Double> renderCreateLatency =
        runConcurrent(
            SAMPLE_COUNT,
            index -> {
              RenderFixture renderFixture = renderFixtures.get(index);
              MvcResult clipResult =
                  mockMvc
                      .perform(
                          authorized(post("/api/videos/{videoId}/clips", renderFixture.videoId()))
                              .contentType(MediaType.APPLICATION_JSON)
                              .content(
                                  objectMapper.writeValueAsString(
                                      Map.of(
                                          "candidateId", renderFixture.candidateId(),
                                          "aspectRatio", "9:16",
                                          "captionPreset", "MINIMAL"))))
                      .andExpect(status().isAccepted())
                      .andReturn();
              UUID clipId = UUID.fromString(json(clipResult).path("id").asText());
              mockMvc
                  .perform(
                      authorized(post("/api/clips/{clipId}/renders", clipId))
                          .contentType(MediaType.APPLICATION_JSON)
                          .content("{\"editVersion\":1}"))
                  .andExpect(status().isAccepted());
            });

    List<Double> transcriptionRequestLatency =
        runConcurrent(
            renderFixtures.size(),
            index ->
                mockMvc
                    .perform(
                        authorized(
                                post(
                                    "/api/videos/{videoId}/transcription",
                                    renderFixtures.get(index).videoId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isAccepted()));

    long databaseBytesDelta = databaseBytes() - databaseBytesBefore;
    long databaseTuplesDelta = databaseTuples() - databaseTuplesBefore;
    LOGGER.info(
        "api_load_profile samples={} upload_confirm_p95_ms={} job_create_p95_ms={} "
            + "job_query_p95_ms={} clip_page_p95_ms={} transcription_request_p95_ms={} "
            + "render_create_p95_ms={} database_bytes_delta={} database_tuples_delta={} "
            + "active_work_query_plan={} clip_page_query_plan={}",
        SAMPLE_COUNT,
        percentile95(uploadConfirmLatency),
        percentile95(jobCreateLatency),
        percentile95(jobQueryLatency),
        percentile95(clipPageLatency),
        percentile95(transcriptionRequestLatency),
        percentile95(renderCreateLatency),
        databaseBytesDelta,
        databaseTuplesDelta,
        activeWorkQueryPlan,
        clipPageQueryPlan);

    assertThat(percentile95(uploadConfirmLatency)).isLessThan(10_000);
    assertThat(percentile95(jobCreateLatency)).isLessThan(10_000);
    assertThat(percentile95(jobQueryLatency)).isLessThan(10_000);
    assertThat(percentile95(clipPageLatency)).isLessThan(10_000);
    assertThat(percentile95(transcriptionRequestLatency)).isLessThan(10_000);
    assertThat(percentile95(renderCreateLatency)).isLessThan(10_000);
    assertThat(databaseBytesDelta).isGreaterThanOrEqualTo(0);
    assertThat(databaseTuplesDelta).isGreaterThan(0);
  }

  private RenderFixture seedRenderFixture() {
    UUID videoId = UUID.randomUUID();
    UUID transcriptionId = UUID.randomUUID();
    UUID analysisRunId = UUID.randomUUID();
    UUID candidateId = UUID.randomUUID();
    Instant now = Instant.now();
    Timestamp timestamp = Timestamp.from(now);
    String sourceKey = "users/" + userId + "/projects/" + projectId + "/load/" + videoId + ".mp4";
    jdbcTemplate.update(
        "INSERT INTO videos (id, user_id, project_id, object_key, original_filename, declared_content_type, "
            + "declared_size_bytes, actual_size_bytes, duration_seconds, width, height, frame_rate, has_audio, "
            + "upload_status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'video/mp4', 2048, 2048, 60, "
            + "1280, 720, 30, true, 'READY', ?, ?)",
        videoId,
        userId,
        projectId,
        sourceKey,
        "load-render.mp4",
        timestamp,
        timestamp);
    jdbcTemplate.update(
        "INSERT INTO transcriptions (id, video_id, user_id, pipeline_version, provider, language, transcript_text, "
            + "duration_seconds, confidence, status, created_at, updated_at, completed_at) "
            + "VALUES (?, ?, ?, 1, 'fake', 'en', 'load fixture transcript', 60, 0.9, 'COMPLETED', ?, ?, ?)",
        transcriptionId,
        videoId,
        userId,
        timestamp,
        timestamp,
        timestamp);
    jdbcTemplate.update(
        "INSERT INTO clip_analysis_runs (id, video_id, user_id, pipeline_version, duration_preference, "
            + "duration_seconds, language, status, created_at, updated_at, completed_at) "
            + "VALUES (?, ?, ?, 1, 'AUTO', 60, 'en', 'COMPLETED', ?, ?, ?)",
        analysisRunId,
        videoId,
        userId,
        timestamp,
        timestamp,
        timestamp);
    jdbcTemplate.update(
        "INSERT INTO clip_candidates (id, analysis_run_id, video_id, user_id, variant, status, start_seconds, "
            + "end_seconds, group_key, title, description, justification, hook_score, context_score, "
            + "development_score, payoff_score, independence_score, engagement_score, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, 'COMPLETE', 'SELECTED', 0, 15, 'load-group', 'Load candidate', "
            + "'Synthetic load fixture', 'Performance profile seed', 0.8, 0.8, 0.8, 0.8, 0.8, 0.8, ?, ?)",
        candidateId,
        analysisRunId,
        videoId,
        userId,
        timestamp,
        timestamp);
    return new RenderFixture(videoId, candidateId);
  }

  private void seedHistoricalCompletedJobs(UUID videoId, int count) {
    UUID pipelineId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    jdbcTemplate.update(
        "INSERT INTO pipelines (id, user_id, project_id, video_id, version, status, correlation_id, "
            + "created_at, updated_at) VALUES (?, ?, ?, ?, 1, 'COMPLETED', ?, ?, ?)",
        pipelineId,
        userId,
        projectId,
        videoId,
        correlationId,
        now,
        now);
    jdbcTemplate.update(
        "INSERT INTO jobs (id, pipeline_id, user_id, project_id, video_id, operation, version, "
            + "idempotency_key, status, current_stage, attempt, progress, correlation_id, created_at, "
            + "updated_at, completed_at) SELECT gen_random_uuid(), ?, ?, ?, ?, 'VIDEO_VALIDATION', 1, "
            + "'historical-load-' || sequence, 'COMPLETED', 'INGEST', 1, 100, ?, "
            + "?::timestamptz - sequence * INTERVAL '1 second', "
            + "?::timestamptz - sequence * INTERVAL '1 second', "
            + "?::timestamptz - sequence * INTERVAL '1 second' "
            + "FROM generate_series(1, ?) AS generated(sequence)",
        pipelineId,
        userId,
        projectId,
        videoId,
        correlationId,
        now,
        now,
        now,
        count);
    jdbcTemplate.execute("ANALYZE jobs");
  }

  private void seedHistoricalCompletedDomainRows(RenderFixture fixture, int count) {
    Timestamp now = Timestamp.from(Instant.now());
    jdbcTemplate.update(
        "INSERT INTO transcriptions (id, video_id, user_id, pipeline_version, provider, language, "
            + "transcript_text, duration_seconds, confidence, status, created_at, updated_at, completed_at) "
            + "SELECT gen_random_uuid(), ?, ?, version, 'fake', 'en', 'historical transcript', 60, 0.9, "
            + "'COMPLETED', ?, ?, ? FROM generate_series(2, ?) AS versions(version)",
        fixture.videoId(),
        userId,
        now,
        now,
        now,
        count + 1);
    jdbcTemplate.update(
        "INSERT INTO clip_analysis_runs (id, video_id, user_id, pipeline_version, duration_preference, "
            + "duration_seconds, language, status, created_at, updated_at, completed_at) "
            + "SELECT gen_random_uuid(), ?, ?, version, 'AUTO', 60, 'en', 'COMPLETED', ?, ?, ? "
            + "FROM generate_series(2, ?) AS versions(version)",
        fixture.videoId(),
        userId,
        now,
        now,
        now,
        count + 1);
    jdbcTemplate.update(
        "WITH historical_clips AS ("
            + "INSERT INTO clips (id, user_id, project_id, video_id, candidate_id, hook_score, context_score, "
            + "development_score, payoff_score, independence_score, engagement_score, current_edit_version, "
            + "status, generation_progress, created_at, updated_at) "
            + "SELECT gen_random_uuid(), ?, ?, ?, ?, 0.8, 0.8, 0.8, 0.8, 0.8, 0.8, 1, 'FAILED', 100, "
            + "?::timestamptz - sequence * INTERVAL '1 second', "
            + "?::timestamptz - sequence * INTERVAL '1 second' "
            + "FROM generate_series(1, ?) AS generated(sequence) "
            + "RETURNING id, user_id, project_id, video_id, candidate_id, current_edit_version, created_at) "
            + "INSERT INTO clip_versions (id, clip_id, user_id, project_id, video_id, candidate_id, "
            + "edit_version, start_seconds, end_seconds, aspect_ratio, caption_preset, font_family, font_size, "
            + "font_weight, text_color, background_color, background_opacity, caption_position, "
            + "caption_animation, created_at) "
            + "SELECT gen_random_uuid(), id, user_id, project_id, video_id, candidate_id, "
            + "current_edit_version, 0, 15, '9:16', 'MINIMAL', 'Arial', 48, 700, '#FFFFFF', '#000000', "
            + "0.5, 'BOTTOM', 'NONE', created_at FROM historical_clips",
        userId,
        projectId,
        fixture.videoId(),
        fixture.candidateId(),
        now,
        now,
        count);
    jdbcTemplate.execute("ANALYZE transcriptions");
    jdbcTemplate.execute("ANALYZE clip_analysis_runs");
    jdbcTemplate.execute("ANALYZE clips");
  }

  private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
    return request.header("Authorization", "Bearer " + accessToken);
  }

  private JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private List<Double> runConcurrent(int requestCount, IndexedLoadAction action) throws Exception {
    var executor = Executors.newFixedThreadPool(THREAD_COUNT);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Double>> futures = new ArrayList<>(requestCount);
    try {
      for (int index = 0; index < requestCount; index++) {
        int requestIndex = index;
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  long requestStarted = System.nanoTime();
                  action.execute(requestIndex);
                  return elapsedMillis(requestStarted);
                }));
      }
      start.countDown();
      List<Double> durations = new ArrayList<>(requestCount);
      for (Future<Double> future : futures) {
        durations.add(future.get(60, TimeUnit.SECONDS));
      }
      return durations;
    } finally {
      executor.shutdownNow();
    }
  }

  private long databaseBytes() {
    Long value = jdbcTemplate.queryForObject("SELECT pg_database_size(current_database())", Long.class);
    return value == null ? 0 : value;
  }

  private long databaseTuples() {
    Long value =
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(n_tup_ins + n_tup_upd + n_tup_del), 0) FROM pg_stat_user_tables",
            Long.class);
    return value == null ? 0 : value;
  }

  private String explainActiveWorkQuery() {
    List<String> planLines =
        jdbcTemplate.queryForList(
            "EXPLAIN (ANALYZE, BUFFERS) SELECT "
                + "(SELECT COUNT(*) FROM jobs WHERE user_id = ? AND status IN ('QUEUED','PROCESSING')) + "
                + "(SELECT COUNT(*) FROM transcriptions WHERE user_id = ? AND status IN ('QUEUED','PROCESSING','RETRYING')) + "
                + "(SELECT COUNT(*) FROM clip_analysis_runs WHERE user_id = ? AND status IN ('QUEUED','PROCESSING','RETRYING')) + "
                + "(SELECT COUNT(*) FROM clips WHERE user_id = ? AND status IN ('QUEUED','PROCESSING')) + "
                + "(SELECT COUNT(*) FROM clip_renders WHERE user_id = ? AND status IN ('QUEUED','PROCESSING'))",
            String.class,
            userId,
            userId,
            userId,
            userId,
            userId);
    return summarizePlan(planLines);
  }

  private String explainClipPageQuery(RenderFixture fixture, int page, int size) {
    List<String> planLines =
        jdbcTemplate.queryForList(
            "EXPLAIN (ANALYZE, BUFFERS) WITH page AS MATERIALIZED ("
                + "SELECT c.id, c.created_at FROM clips c WHERE c.video_id = ? AND c.user_id = ? "
                + "ORDER BY c.created_at DESC, c.id LIMIT ? OFFSET ?) "
                + "SELECT c.id FROM page p JOIN clips c ON c.id = p.id "
                + "JOIN clip_versions v ON v.clip_id = c.id AND v.edit_version = c.current_edit_version "
                + "ORDER BY p.created_at DESC, p.id",
            String.class,
            fixture.videoId(),
            userId,
            size,
            page * size);
    return summarizePlan(planLines);
  }

  private static String summarizePlan(List<String> planLines) {
    return String.join(
        " | ",
        planLines.stream()
            .filter(
                line ->
                    line.contains("Seq Scan on ")
                        || line.contains("Sort ")
                        || line.contains("Index Scan using ")
                        || line.contains("Index Only Scan using ")
                        || line.trim().startsWith("Execution Time:"))
            .map(ApiLoadProfileTest::planNodeSummary)
            .toList());
  }

  private static String planNodeSummary(String line) {
    String summary = line.trim();
    if (!summary.startsWith("Execution Time:")) {
      int arrow = summary.lastIndexOf("->");
      if (arrow >= 0) {
        summary = summary.substring(arrow + 2).trim();
      }
      int cost = summary.indexOf(" (cost=");
      if (cost >= 0) {
        summary = summary.substring(0, cost).trim();
      }
    }
    return summary;
  }

  private static double elapsedMillis(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000.0;
  }

  private static double percentile95(List<Double> samples) {
    List<Double> ordered = samples.stream().sorted().toList();
    int index = Math.max(0, (int) Math.ceil(ordered.size() * 0.95) - 1);
    return ordered.get(index);
  }

  private record RenderFixture(UUID videoId, UUID candidateId) {}

  @FunctionalInterface
  private interface IndexedLoadAction {
    void execute(int index) throws Exception;
  }
}
