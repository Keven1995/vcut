package com.vcut.api.clip.infrastructure;

import com.vcut.api.clip.application.ClipAggregate;
import com.vcut.api.clip.application.ClipPage;
import com.vcut.api.clip.application.ClipRepository;
import com.vcut.api.clip.domain.AspectRatio;
import com.vcut.api.clip.domain.CaptionAnimation;
import com.vcut.api.clip.domain.CaptionCue;
import com.vcut.api.clip.domain.CaptionPosition;
import com.vcut.api.clip.domain.CaptionPreset;
import com.vcut.api.clip.domain.CaptionStyle;
import com.vcut.api.clip.domain.Clip;
import com.vcut.api.clip.domain.ClipScore;
import com.vcut.api.clip.domain.ClipStatus;
import com.vcut.api.clip.domain.ClipVersion;
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
public class JdbcClipRepository implements ClipRepository {

  private static final String AGGREGATE_COLUMNS =
      "c.id AS clip_id, c.user_id AS clip_user_id, c.project_id AS clip_project_id, "
          + "c.video_id AS clip_video_id, c.candidate_id AS clip_candidate_id, "
          + "c.hook_score, c.context_score, c.development_score, c.payoff_score, "
          + "c.independence_score, c.engagement_score, c.current_edit_version, c.status, "
          + "c.output_object_key, c.output_width, c.output_height, c.output_duration_seconds, "
          + "c.output_aspect_ratio, c.output_edit_version, c.error_code, c.error_message, "
          + "c.created_at AS clip_created_at, c.updated_at AS clip_updated_at, "
          + "v.id AS version_id, v.clip_id AS version_clip_id, v.user_id AS version_user_id, "
          + "v.project_id AS version_project_id, v.video_id AS version_video_id, "
          + "v.candidate_id AS version_candidate_id, v.edit_version, v.start_seconds, v.end_seconds, "
          + "v.aspect_ratio, v.caption_preset, v.font_family, v.font_size, v.font_weight, "
          + "v.text_color, v.background_color, v.background_opacity, v.caption_position, "
          + "v.caption_animation, v.created_at AS version_created_at";

  private final JdbcTemplate jdbcTemplate;

  public JdbcClipRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Clip save(Clip clip) {
    jdbcTemplate.update(
        "INSERT INTO clips (id, user_id, project_id, video_id, candidate_id, hook_score, context_score, "
            + "development_score, payoff_score, independence_score, engagement_score, current_edit_version, "
            + "status, output_object_key, output_width, output_height, output_duration_seconds, "
            + "output_aspect_ratio, output_edit_version, error_code, error_message, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        clip.id(),
        clip.userId(),
        clip.projectId(),
        clip.videoId(),
        clip.candidateId(),
        clip.score().hook(),
        clip.score().context(),
        clip.score().development(),
        clip.score().payoff(),
        clip.score().independence(),
        clip.score().engagement(),
        clip.currentEditVersion(),
        clip.status().name(),
        clip.outputObjectKey(),
        clip.outputWidth(),
        clip.outputHeight(),
        clip.outputDurationSeconds(),
        value(clip.outputAspectRatio()),
        clip.outputEditVersion(),
        clip.errorCode(),
        clip.errorMessage(),
        Timestamp.from(clip.createdAt()),
        Timestamp.from(clip.updatedAt()));
    return clip;
  }

  @Override
  public ClipVersion saveVersion(ClipVersion version) {
    jdbcTemplate.update(
        "INSERT INTO clip_versions (id, clip_id, user_id, project_id, video_id, candidate_id, edit_version, "
            + "start_seconds, end_seconds, aspect_ratio, caption_preset, font_family, font_size, font_weight, "
            + "text_color, background_color, background_opacity, caption_position, caption_animation, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        version.id(),
        version.clipId(),
        version.userId(),
        version.projectId(),
        version.videoId(),
        version.candidateId(),
        version.editVersion(),
        version.startSeconds(),
        version.endSeconds(),
        version.aspectRatio().value(),
        version.captionPreset().name(),
        version.captionStyle().fontFamily(),
        version.captionStyle().fontSize(),
        version.captionStyle().fontWeight(),
        version.captionStyle().textColor(),
        version.captionStyle().backgroundColor(),
        version.captionStyle().backgroundOpacity(),
        version.captionStyle().position().name(),
        version.captionStyle().animation().name(),
        Timestamp.from(version.createdAt()));
    for (int position = 0; position < version.captionCues().size(); position++) {
      CaptionCue cue = version.captionCues().get(position);
      jdbcTemplate.update(
          "INSERT INTO caption_cues (id, clip_version_id, position, cue_text, start_seconds, end_seconds) "
              + "VALUES (?, ?, ?, ?, ?, ?)",
          cue.id(),
          version.id(),
          position,
          cue.text(),
          cue.startSeconds(),
          cue.endSeconds());
    }
    return version;
  }

  @Override
  public void update(Clip clip) {
    jdbcTemplate.update(
        "UPDATE clips SET generation_requested_version = CASE WHEN current_edit_version <> ? "
            + "THEN NULL ELSE generation_requested_version END, current_edit_version = ?, status = ?, output_object_key = ?, output_width = ?, "
            + "output_height = ?, output_duration_seconds = ?, output_aspect_ratio = ?, output_edit_version = ?, "
            + "error_code = ?, error_message = ?, updated_at = ? WHERE id = ? AND user_id = ?",
        clip.currentEditVersion(),
        clip.currentEditVersion(),
        clip.status().name(),
        clip.outputObjectKey(),
        clip.outputWidth(),
        clip.outputHeight(),
        clip.outputDurationSeconds(),
        value(clip.outputAspectRatio()),
        clip.outputEditVersion(),
        clip.errorCode(),
        clip.errorMessage(),
        Timestamp.from(clip.updatedAt()),
        clip.id(),
        clip.userId());
  }

  @Override
  public boolean claimGeneration(UUID clipId, UUID userId, int editVersion, Instant now) {
    int updated =
        jdbcTemplate.update(
            "UPDATE clips SET generation_requested_version = current_edit_version, status = 'QUEUED', "
                + "output_object_key = NULL, output_width = NULL, output_height = NULL, "
                + "output_duration_seconds = NULL, output_aspect_ratio = NULL, output_edit_version = NULL, "
                + "error_code = NULL, error_message = NULL, updated_at = ? "
                + "WHERE id = ? AND user_id = ? AND current_edit_version = ? "
                + "AND (generation_requested_version IS NULL OR generation_requested_version <> current_edit_version)",
            Timestamp.from(now),
            clipId,
            userId,
            editVersion);
    return updated == 1;
  }

  @Override
  public Optional<ClipAggregate> findById(UUID clipId) {
    return queryAggregate("WHERE c.id = ?", clipId);
  }

  @Override
  public Optional<ClipAggregate> findByIdForUser(UUID clipId, UUID userId) {
    return queryAggregate("WHERE c.id = ? AND c.user_id = ?", clipId, userId);
  }

  @Override
  public ClipPage findByVideoForUser(
      UUID videoId, UUID userId, int page, int size, ClipStatus status) {
    String filter = " WHERE c.video_id = ? AND c.user_id = ?";
    Object[] filterArguments =
        status == null
            ? new Object[] {videoId, userId}
            : new Object[] {videoId, userId, status.name()};
    if (status != null) {
      filter += " AND c.status = ?";
    }
    Long total =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM clips c" + filter, Long.class, filterArguments);
    Object[] queryArguments =
        status == null
            ? new Object[] {videoId, userId, size, page * size}
            : new Object[] {videoId, userId, status.name(), size, page * size};
    List<ClipAggregate> content =
        jdbcTemplate.query(
            "SELECT "
                + AGGREGATE_COLUMNS
                + " FROM clips c JOIN clip_versions v ON v.clip_id = c.id AND v.edit_version = c.current_edit_version"
                + filter
                + " ORDER BY c.created_at DESC, c.id LIMIT ? OFFSET ?",
            (resultSet, rowNumber) -> mapAggregate(resultSet),
            queryArguments);
    return new ClipPage(content, page, size, total == null ? 0 : total);
  }

  private Optional<ClipAggregate> queryAggregate(String filter, Object... arguments) {
    return jdbcTemplate
        .query(
            "SELECT "
                + AGGREGATE_COLUMNS
                + " FROM clips c JOIN clip_versions v ON v.clip_id = c.id AND v.edit_version = c.current_edit_version "
                + filter,
            (resultSet, rowNumber) -> mapAggregate(resultSet),
            arguments)
        .stream()
        .findFirst();
  }

  private ClipAggregate mapAggregate(ResultSet resultSet) throws SQLException {
    Clip clip = mapClip(resultSet);
    ClipVersion version =
        mapVersion(resultSet, loadCues(resultSet.getObject("version_id", UUID.class)));
    return new ClipAggregate(clip, version);
  }

  private List<CaptionCue> loadCues(UUID versionId) {
    return jdbcTemplate.query(
        "SELECT id, cue_text, start_seconds, end_seconds FROM caption_cues "
            + "WHERE clip_version_id = ? ORDER BY position",
        (resultSet, rowNumber) ->
            new CaptionCue(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("cue_text"),
                resultSet.getBigDecimal("start_seconds"),
                resultSet.getBigDecimal("end_seconds")),
        versionId);
  }

  private static Clip mapClip(ResultSet resultSet) throws SQLException {
    return new Clip(
        resultSet.getObject("clip_id", UUID.class),
        resultSet.getObject("clip_user_id", UUID.class),
        resultSet.getObject("clip_project_id", UUID.class),
        resultSet.getObject("clip_video_id", UUID.class),
        resultSet.getObject("clip_candidate_id", UUID.class),
        new ClipScore(
            resultSet.getBigDecimal("hook_score"),
            resultSet.getBigDecimal("context_score"),
            resultSet.getBigDecimal("development_score"),
            resultSet.getBigDecimal("payoff_score"),
            resultSet.getBigDecimal("independence_score"),
            resultSet.getBigDecimal("engagement_score")),
        resultSet.getInt("current_edit_version"),
        ClipStatus.valueOf(resultSet.getString("status")),
        resultSet.getString("output_object_key"),
        nullableInteger(resultSet, "output_width"),
        nullableInteger(resultSet, "output_height"),
        resultSet.getBigDecimal("output_duration_seconds"),
        nullableAspectRatio(resultSet.getString("output_aspect_ratio")),
        nullableInteger(resultSet, "output_edit_version"),
        resultSet.getString("error_code"),
        resultSet.getString("error_message"),
        resultSet.getTimestamp("clip_created_at").toInstant(),
        resultSet.getTimestamp("clip_updated_at").toInstant());
  }

  private static ClipVersion mapVersion(ResultSet resultSet, List<CaptionCue> cues)
      throws SQLException {
    return new ClipVersion(
        resultSet.getObject("version_id", UUID.class),
        resultSet.getObject("version_clip_id", UUID.class),
        resultSet.getObject("version_user_id", UUID.class),
        resultSet.getObject("version_project_id", UUID.class),
        resultSet.getObject("version_video_id", UUID.class),
        resultSet.getObject("version_candidate_id", UUID.class),
        resultSet.getInt("edit_version"),
        resultSet.getBigDecimal("start_seconds"),
        resultSet.getBigDecimal("end_seconds"),
        AspectRatio.fromValue(resultSet.getString("aspect_ratio")),
        CaptionPreset.valueOf(resultSet.getString("caption_preset")),
        new CaptionStyle(
            resultSet.getString("font_family"),
            resultSet.getInt("font_size"),
            resultSet.getInt("font_weight"),
            resultSet.getString("text_color"),
            resultSet.getString("background_color"),
            resultSet.getBigDecimal("background_opacity"),
            CaptionPosition.valueOf(resultSet.getString("caption_position")),
            CaptionAnimation.valueOf(resultSet.getString("caption_animation"))),
        cues,
        resultSet.getTimestamp("version_created_at").toInstant());
  }

  private static String value(AspectRatio value) {
    return value == null ? null : value.value();
  }

  private static AspectRatio nullableAspectRatio(String value) {
    return value == null ? null : AspectRatio.fromValue(value);
  }

  private static Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
    int value = resultSet.getInt(column);
    return resultSet.wasNull() ? null : value;
  }
}
