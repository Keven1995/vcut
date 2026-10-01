package com.vcut.api.job.infrastructure;

import com.vcut.api.job.application.StageRunRepository;
import com.vcut.api.job.domain.StageRun;
import com.vcut.api.job.domain.StageRunStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcStageRunRepository implements StageRunRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcStageRunRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public StageRun save(StageRun stageRun) {
    jdbcTemplate.update(
        "INSERT INTO stage_runs (id, job_id, pipeline_version, stage_name, status, attempt, progress, input_payload, output_payload, "
            + "error_code, error_message, started_at, finished_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        stageRun.id(),
        stageRun.jobId(),
        stageRun.pipelineVersion(),
        stageRun.stageName(),
        stageRun.status().name(),
        stageRun.attempt(),
        stageRun.progress(),
        stageRun.inputPayload(),
        stageRun.outputPayload(),
        stageRun.errorCode(),
        stageRun.errorMessage(),
        timestamp(stageRun.startedAt()),
        timestamp(stageRun.finishedAt()),
        Timestamp.from(stageRun.createdAt()),
        Timestamp.from(stageRun.updatedAt()));
    return stageRun;
  }

  @Override
  public void updateProgress(
      UUID jobId,
      String stageName,
      StageRunStatus status,
      int attempt,
      double progress,
      Instant now) {
    jdbcTemplate.update(
        "UPDATE stage_runs SET status = ?, attempt = ?, progress = ?, started_at = COALESCE(started_at, ?), updated_at = ? "
            + "WHERE job_id = ? AND stage_name = ?",
        status.name(),
        attempt,
        progress,
        Timestamp.from(now),
        Timestamp.from(now),
        jobId,
        stageName);
  }

  @Override
  public void complete(
      UUID jobId,
      String stageName,
      StageRunStatus status,
      String outputPayload,
      String errorCode,
      String errorMessage,
      Instant now) {
    jdbcTemplate.update(
        "UPDATE stage_runs SET status = ?, progress = ?, output_payload = ?, error_code = ?, error_message = ?, "
            + "finished_at = ?, updated_at = ? WHERE job_id = ? AND stage_name = ?",
        status.name(),
        status == StageRunStatus.COMPLETED ? 100 : 0,
        outputPayload,
        errorCode,
        errorMessage,
        Timestamp.from(now),
        Timestamp.from(now),
        jobId,
        stageName);
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }
}
