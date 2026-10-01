package com.vcut.api.job.infrastructure;

import com.vcut.api.job.application.PipelineRepository;
import com.vcut.api.job.domain.Pipeline;
import com.vcut.api.job.domain.PipelineStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPipelineRepository implements PipelineRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcPipelineRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Pipeline save(Pipeline pipeline) {
    jdbcTemplate.update(
        "INSERT INTO pipelines (id, user_id, project_id, video_id, version, status, correlation_id, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        pipeline.id(),
        pipeline.userId(),
        pipeline.projectId(),
        pipeline.videoId(),
        pipeline.version(),
        pipeline.status().name(),
        pipeline.correlationId(),
        Timestamp.from(pipeline.createdAt()),
        Timestamp.from(pipeline.updatedAt()));
    return pipeline;
  }

  @Override
  public void updateStatus(Pipeline pipeline) {
    jdbcTemplate.update(
        "UPDATE pipelines SET status = ?, updated_at = ? WHERE id = ?",
        pipeline.status().name(),
        Timestamp.from(pipeline.updatedAt()),
        pipeline.id());
  }

  @Override
  public void updateStatus(UUID pipelineId, PipelineStatus status, Instant now) {
    jdbcTemplate.update(
        "UPDATE pipelines SET status = ?, updated_at = ? WHERE id = ?",
        status.name(),
        Timestamp.from(now),
        pipelineId);
  }
}
