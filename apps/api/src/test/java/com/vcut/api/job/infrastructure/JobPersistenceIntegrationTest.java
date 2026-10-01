package com.vcut.api.job.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JobPersistenceIntegrationTest {

  @Container
  static final GenericContainer<?> POSTGRES =
      new GenericContainer<>("postgres:16.6-alpine")
          .withEnv("POSTGRES_USER", "test")
          .withEnv("POSTGRES_PASSWORD", "test")
          .withEnv("POSTGRES_DB", "test")
          .withExposedPorts(5432)
          .waitingFor(Wait.forListeningPort());

  private static JdbcTemplate jdbc;

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(jdbcUrl(), "test", "test")
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl());
    dataSource.setUsername("test");
    dataSource.setPassword("test");
    jdbc = new JdbcTemplate(dataSource);
  }

  private static String jdbcUrl() {
    return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/test";
  }

  @Test
  void persistsCorrelatedPipelineJobStageAndOutboxRecords() {
    UUID userId = UUID.randomUUID();
    UUID projectId = UUID.randomUUID();
    UUID videoId = UUID.randomUUID();
    UUID pipelineId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    UUID stageId = UUID.randomUUID();
    UUID outboxId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    Timestamp now = Timestamp.from(java.time.Instant.parse("2026-09-30T12:00:00Z"));

    jdbc.update(
        "INSERT INTO users (id, email, normalized_email, password_hash, status, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
        userId,
        userId + "@example.test",
        userId + "@example.test",
        "test-hash",
        now,
        now);
    jdbc.update(
        "INSERT INTO projects (id, user_id, name, status, created_at, updated_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
        projectId,
        userId,
        "Persistence test",
        now,
        now);
    jdbc.update(
        "INSERT INTO videos (id, user_id, project_id, object_key, original_filename, declared_content_type, declared_size_bytes, upload_status, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, 'UPLOADED', ?, ?)",
        videoId,
        userId,
        projectId,
        "users/" + userId + "/projects/" + projectId + "/source/" + videoId + "/original.mp4",
        "fixture.mp4",
        "video/mp4",
        100L,
        now,
        now);
    jdbc.update(
        "INSERT INTO pipelines (id, user_id, project_id, video_id, version, status, correlation_id, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, 1, 'QUEUED', ?, ?, ?)",
        pipelineId,
        userId,
        projectId,
        videoId,
        correlationId,
        now,
        now);
    jdbc.update(
        "INSERT INTO jobs (id, pipeline_id, user_id, project_id, video_id, operation, version, idempotency_key, status, current_stage, attempt, progress, correlation_id, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, 'VIDEO_VALIDATION', 1, ?, 'QUEUED', 'INGEST', 1, 0, ?, ?, ?)",
        jobId,
        pipelineId,
        userId,
        projectId,
        videoId,
        videoId + ":VIDEO_VALIDATION:1",
        correlationId,
        now,
        now);
    jdbc.update(
        "INSERT INTO stage_runs (id, job_id, stage_name, status, attempt, progress, input_payload, created_at, updated_at) "
            + "VALUES (?, ?, 'INGEST', 'QUEUED', 1, 0, ?, ?, ?)",
        stageId,
        jobId,
        "{\"jobId\":\"" + jobId + "\"}",
        now,
        now);
    jdbc.update(
        "INSERT INTO outbox_messages (id, aggregate_type, aggregate_id, event_type, routing_key, payload, attempt, available_at, created_at) "
            + "VALUES (?, 'JOB', ?, 'VideoValidationRequested', 'pipeline.video.validate', ?, 0, ?, ?)",
        outboxId,
        jobId,
        "{\"jobId\":\"" + jobId + "\",\"correlationId\":\"" + correlationId + "\"}",
        now,
        now);

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT p.correlation_id, j.status AS job_status, s.status AS stage_status, o.published_at "
                + "FROM pipelines p JOIN jobs j ON j.pipeline_id = p.id "
                + "JOIN stage_runs s ON s.job_id = j.id "
                + "JOIN outbox_messages o ON o.aggregate_id = j.id WHERE j.id = ?",
            jobId);

    assertThat(row.get("correlation_id")).isEqualTo(correlationId);
    assertThat(row.get("job_status")).isEqualTo("QUEUED");
    assertThat(row.get("stage_status")).isEqualTo("QUEUED");
    assertThat(row.get("published_at")).isNull();
  }
}
