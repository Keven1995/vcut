package com.vcut.api.job.infrastructure;

import com.vcut.api.job.application.JobRepository;
import com.vcut.api.job.domain.Job;
import com.vcut.api.job.domain.JobStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcJobRepository implements JobRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcJobRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Job save(Job job) {
    jdbcTemplate.update(
        "INSERT INTO jobs (id, pipeline_id, user_id, project_id, video_id, operation, version, idempotency_key, "
            + "status, current_stage, attempt, progress, correlation_id, error_code, error_message, created_at, updated_at, completed_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        job.id(),
        job.pipelineId(),
        job.userId(),
        job.projectId(),
        job.videoId(),
        job.operation(),
        job.version(),
        job.idempotencyKey(),
        job.status().name(),
        job.currentStage(),
        job.attempt(),
        job.progress(),
        job.correlationId(),
        job.errorCode(),
        job.errorMessage(),
        Timestamp.from(job.createdAt()),
        Timestamp.from(job.updatedAt()),
        timestamp(job.completedAt()));
    return job;
  }

  @Override
  public Optional<Job> findByIdForUser(UUID jobId, UUID userId) {
    return jdbcTemplate
        .query(
            "SELECT * FROM jobs WHERE id = ? AND user_id = ?",
            JdbcJobRepository::mapJob,
            jobId,
            userId)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<Job> findById(UUID jobId) {
    return jdbcTemplate
        .query("SELECT * FROM jobs WHERE id = ?", JdbcJobRepository::mapJob, jobId)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<Job> findByIdempotencyKeyForUser(String idempotencyKey, UUID userId) {
    return jdbcTemplate
        .query(
            "SELECT * FROM jobs WHERE idempotency_key = ? AND user_id = ?",
            JdbcJobRepository::mapJob,
            idempotencyKey,
            userId)
        .stream()
        .findFirst();
  }

  @Override
  public void updateProgress(
      UUID jobId, JobStatus status, String stage, int attempt, double progress, Instant now) {
    jdbcTemplate.update(
        "UPDATE jobs SET status = ?, current_stage = ?, attempt = ?, progress = ?, updated_at = ? WHERE id = ?",
        status.name(),
        stage,
        attempt,
        progress,
        Timestamp.from(now),
        jobId);
  }

  @Override
  public void complete(
      UUID jobId,
      JobStatus status,
      String stage,
      double progress,
      String errorCode,
      String errorMessage,
      Instant now) {
    jdbcTemplate.update(
        "UPDATE jobs SET status = ?, current_stage = ?, progress = ?, error_code = ?, error_message = ?, "
            + "updated_at = ?, completed_at = ? WHERE id = ?",
        status.name(),
        stage,
        progress,
        errorCode,
        errorMessage,
        Timestamp.from(now),
        Timestamp.from(now),
        jobId);
  }

  private static Job mapJob(ResultSet resultSet, int rowNumber) throws SQLException {
    return new Job(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("pipeline_id", UUID.class),
        resultSet.getObject("user_id", UUID.class),
        resultSet.getObject("project_id", UUID.class),
        resultSet.getObject("video_id", UUID.class),
        resultSet.getString("operation"),
        resultSet.getInt("version"),
        resultSet.getString("idempotency_key"),
        JobStatus.valueOf(resultSet.getString("status")),
        resultSet.getString("current_stage"),
        resultSet.getInt("attempt"),
        resultSet.getDouble("progress"),
        resultSet.getObject("correlation_id", UUID.class),
        resultSet.getString("error_code"),
        resultSet.getString("error_message"),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant(),
        nullableInstant(resultSet, "completed_at"));
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
    Timestamp timestamp = resultSet.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }
}
