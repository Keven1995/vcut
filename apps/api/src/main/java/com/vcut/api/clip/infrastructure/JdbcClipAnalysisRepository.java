package com.vcut.api.clip.infrastructure;

import com.vcut.api.clip.application.ClipAnalysisRepository;
import com.vcut.api.clip.domain.AnalysisRunStatus;
import com.vcut.api.clip.domain.CandidateAction;
import com.vcut.api.clip.domain.CandidateStatus;
import com.vcut.api.clip.domain.CandidateVariant;
import com.vcut.api.clip.domain.ClipAnalysisRun;
import com.vcut.api.clip.domain.ClipCandidate;
import com.vcut.api.clip.domain.DurationPreference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcClipAnalysisRepository implements ClipAnalysisRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcClipAnalysisRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public int nextVersion(UUID videoId) {
    Integer version =
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(pipeline_version), 0) + 1 FROM clip_analysis_runs WHERE video_id = ?",
            Integer.class,
            videoId);
    return version == null ? 1 : version;
  }

  @Override
  public ClipAnalysisRun saveRun(ClipAnalysisRun run) {
    jdbcTemplate.update(
        "INSERT INTO clip_analysis_runs (id, video_id, user_id, pipeline_version, duration_preference, "
            + "custom_duration_seconds, duration_seconds, language, status, error_code, error_message, "
            + "created_at, updated_at, completed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        run.id(),
        run.videoId(),
        run.userId(),
        run.pipelineVersion(),
        run.durationPreference().name(),
        run.customDurationSeconds(),
        run.durationSeconds(),
        run.language(),
        run.status().name(),
        run.errorCode(),
        run.errorMessage(),
        Timestamp.from(run.createdAt()),
        Timestamp.from(run.updatedAt()),
        timestamp(run.completedAt()));
    return run;
  }

  @Override
  public Optional<ClipAnalysisRun> findRunForUser(UUID videoId, UUID userId, int pipelineVersion) {
    return jdbcTemplate
        .query(
            "SELECT * FROM clip_analysis_runs WHERE video_id = ? AND user_id = ? AND pipeline_version = ?",
            (resultSet, rowNumber) -> mapRun(resultSet),
            videoId,
            userId,
            pipelineVersion)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<ClipAnalysisRun> findRun(UUID videoId, int pipelineVersion) {
    return jdbcTemplate
        .query(
            "SELECT * FROM clip_analysis_runs WHERE video_id = ? AND pipeline_version = ?",
            (resultSet, rowNumber) -> mapRun(resultSet),
            videoId,
            pipelineVersion)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<ClipAnalysisRun> findRunById(UUID runId) {
    return jdbcTemplate
        .query(
            "SELECT * FROM clip_analysis_runs WHERE id = ?",
            (resultSet, rowNumber) -> mapRun(resultSet),
            runId)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<ClipAnalysisRun> findLatestRunForUser(UUID videoId, UUID userId) {
    return jdbcTemplate
        .query(
            "SELECT * FROM clip_analysis_runs WHERE video_id = ? AND user_id = "
                + "? ORDER BY pipeline_version DESC LIMIT 1",
            (resultSet, rowNumber) -> mapRun(resultSet),
            videoId,
            userId)
        .stream()
        .findFirst();
  }

  @Override
  public List<ClipCandidate> findLatestCandidatesForUser(UUID videoId, UUID userId) {
    return jdbcTemplate.query(
        "SELECT c.* FROM clip_candidates c JOIN clip_analysis_runs r ON r.id = c.analysis_run_id "
            + "WHERE c.video_id = ? AND c.user_id = ? AND r.pipeline_version = "
            + "(SELECT MAX(pipeline_version) FROM clip_analysis_runs WHERE video_id = ? AND user_id = ?) "
            + "ORDER BY (0.2 * c.hook_score + 0.15 * c.context_score + 0.15 * c.development_score "
            + "+ 0.2 * c.payoff_score + 0.15 * c.independence_score + 0.1 * c.engagement_score) DESC, "
            + "c.start_seconds, c.end_seconds, c.id",
        (resultSet, rowNumber) -> mapCandidate(resultSet),
        videoId,
        userId,
        videoId,
        userId);
  }

  @Override
  public Optional<ClipCandidate> findCandidateForUser(UUID candidateId, UUID userId) {
    return jdbcTemplate
        .query(
            "SELECT * FROM clip_candidates WHERE id = ? AND user_id = ?",
            (resultSet, rowNumber) -> mapCandidate(resultSet),
            candidateId,
            userId)
        .stream()
        .findFirst();
  }

  @Override
  public void saveCandidates(List<ClipCandidate> candidates) {
    for (ClipCandidate candidate : candidates) {
      jdbcTemplate.update(
          "INSERT INTO clip_candidates (id, analysis_run_id, video_id, user_id, variant, status, "
              + "start_seconds, end_seconds, group_key, title, description, justification, hook_score, "
              + "context_score, development_score, payoff_score, independence_score, engagement_score, "
              + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
              + "ON CONFLICT (id) DO NOTHING",
          candidate.id(),
          candidate.analysisRunId(),
          candidate.videoId(),
          candidate.userId(),
          candidate.variant().name(),
          candidate.status().name(),
          candidate.startSeconds(),
          candidate.endSeconds(),
          candidate.group(),
          candidate.title(),
          candidate.description(),
          candidate.justification(),
          candidate.hookScore(),
          candidate.contextScore(),
          candidate.developmentScore(),
          candidate.payoffScore(),
          candidate.independenceScore(),
          candidate.engagementScore(),
          Timestamp.from(candidate.createdAt()),
          Timestamp.from(candidate.updatedAt()));
    }
  }

  @Override
  public void updateRunStatus(
      UUID runId, AnalysisRunStatus status, String errorCode, String errorMessage, Instant now) {
    jdbcTemplate.update(
        "UPDATE clip_analysis_runs SET status = ?, error_code = ?, error_message = ?, "
            + "updated_at = ?, completed_at = CASE WHEN ? IN ('COMPLETED', 'FAILED') THEN ? ELSE completed_at END "
            + "WHERE id = ? AND status NOT IN ('COMPLETED', 'FAILED')",
        status.name(),
        errorCode,
        errorMessage,
        Timestamp.from(now),
        status.name(),
        Timestamp.from(now),
        runId);
  }

  @Override
  public void updateCandidateStatus(
      UUID candidateId, UUID userId, CandidateStatus status, Instant now) {
    jdbcTemplate.update(
        "UPDATE clip_candidates SET status = ?, updated_at = ? WHERE id = ? AND user_id = ?",
        status.name(),
        Timestamp.from(now),
        candidateId,
        userId);
  }

  @Override
  public void recordAction(
      UUID candidateId, UUID videoId, UUID userId, CandidateAction action, Instant now) {
    jdbcTemplate.update(
        "INSERT INTO clip_candidate_action_audit (id, candidate_id, video_id, user_id, action, occurred_at) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        candidateId,
        videoId,
        userId,
        action.name(),
        Timestamp.from(now));
    jdbcTemplate.update(
        "INSERT INTO clip_candidate_action_metrics (candidate_id, video_id, user_id, accept_count, "
            + "discard_count, select_count, last_action_at) VALUES (?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (candidate_id, user_id) DO UPDATE SET "
            + "accept_count = clip_candidate_action_metrics.accept_count + EXCLUDED.accept_count, "
            + "discard_count = clip_candidate_action_metrics.discard_count + EXCLUDED.discard_count, "
            + "select_count = clip_candidate_action_metrics.select_count + EXCLUDED.select_count, "
            + "last_action_at = EXCLUDED.last_action_at",
        candidateId,
        videoId,
        userId,
        action == CandidateAction.ACCEPT ? 1 : 0,
        action == CandidateAction.DISCARD ? 1 : 0,
        action == CandidateAction.SELECT ? 1 : 0,
        Timestamp.from(now));
  }

  private static ClipAnalysisRun mapRun(ResultSet resultSet) throws SQLException {
    return new ClipAnalysisRun(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("video_id", UUID.class),
        resultSet.getObject("user_id", UUID.class),
        resultSet.getInt("pipeline_version"),
        DurationPreference.valueOf(resultSet.getString("duration_preference")),
        resultSet.getBigDecimal("custom_duration_seconds"),
        resultSet.getBigDecimal("duration_seconds"),
        resultSet.getString("language"),
        AnalysisRunStatus.valueOf(resultSet.getString("status")),
        resultSet.getString("error_code"),
        resultSet.getString("error_message"),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant(),
        nullableInstant(resultSet, "completed_at"));
  }

  private static ClipCandidate mapCandidate(ResultSet resultSet) throws SQLException {
    return new ClipCandidate(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("analysis_run_id", UUID.class),
        resultSet.getObject("video_id", UUID.class),
        resultSet.getObject("user_id", UUID.class),
        CandidateVariant.valueOf(resultSet.getString("variant")),
        CandidateStatus.valueOf(resultSet.getString("status")),
        resultSet.getBigDecimal("start_seconds"),
        resultSet.getBigDecimal("end_seconds"),
        resultSet.getString("group_key"),
        resultSet.getString("title"),
        resultSet.getString("description"),
        resultSet.getString("justification"),
        resultSet.getBigDecimal("hook_score"),
        resultSet.getBigDecimal("context_score"),
        resultSet.getBigDecimal("development_score"),
        resultSet.getBigDecimal("payoff_score"),
        resultSet.getBigDecimal("independence_score"),
        resultSet.getBigDecimal("engagement_score"),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant());
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
    Timestamp timestamp = resultSet.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }
}
