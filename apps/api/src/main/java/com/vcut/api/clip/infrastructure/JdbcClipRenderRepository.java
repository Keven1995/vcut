package com.vcut.api.clip.infrastructure;

import com.vcut.api.clip.application.ClipRenderRepository;
import com.vcut.api.clip.domain.AspectRatio;
import com.vcut.api.clip.domain.ClipRender;
import com.vcut.api.clip.domain.RenderStatus;
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
public class JdbcClipRenderRepository implements ClipRenderRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcClipRenderRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public ClipRender save(ClipRender render) {
    jdbcTemplate.update(
        "INSERT INTO clip_renders (id, clip_id, user_id, project_id, edit_version, status, progress, "
            + "output_object_key, thumbnail_object_key, output_width, output_height, output_duration_seconds, "
            + "output_aspect_ratio, error_code, error_message, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        render.id(),
        render.clipId(),
        render.userId(),
        render.projectId(),
        render.editVersion(),
        render.status().name(),
        render.progress(),
        render.outputObjectKey(),
        render.thumbnailObjectKey(),
        render.outputWidth(),
        render.outputHeight(),
        render.outputDurationSeconds(),
        value(render.outputAspectRatio()),
        render.errorCode(),
        render.errorMessage(),
        Timestamp.from(render.createdAt()),
        Timestamp.from(render.updatedAt()));
    return render;
  }

  @Override
  public void update(ClipRender render) {
    jdbcTemplate.update(
        "UPDATE clip_renders SET status = ?, progress = ?, output_object_key = ?, "
            + "thumbnail_object_key = ?, output_width = ?, output_height = ?, output_duration_seconds = ?, "
            + "output_aspect_ratio = ?, error_code = ?, error_message = ?, updated_at = ? "
            + "WHERE id = ? AND user_id = ?",
        render.status().name(),
        render.progress(),
        render.outputObjectKey(),
        render.thumbnailObjectKey(),
        render.outputWidth(),
        render.outputHeight(),
        render.outputDurationSeconds(),
        value(render.outputAspectRatio()),
        render.errorCode(),
        render.errorMessage(),
        Timestamp.from(render.updatedAt()),
        render.id(),
        render.userId());
  }

  @Override
  public Optional<ClipRender> findById(UUID renderId) {
    return query("WHERE id = ?", renderId);
  }

  @Override
  public Optional<ClipRender> findByIdForUser(UUID renderId, UUID userId) {
    return query("WHERE id = ? AND user_id = ?", renderId, userId);
  }

  @Override
  public Optional<ClipRender> findByClipAndVersionForUser(
      UUID clipId, int editVersion, UUID userId) {
    return query(
        "WHERE clip_id = ? AND edit_version = ? AND user_id = ?", clipId, editVersion, userId);
  }

  @Override
  public List<ClipRender> findByClipForUser(UUID clipId, UUID userId) {
    return jdbcTemplate.query(
        "SELECT * FROM clip_renders WHERE clip_id = ? AND user_id = ? "
            + "ORDER BY edit_version DESC, created_at DESC",
        (resultSet, rowNumber) -> map(resultSet),
        clipId,
        userId);
  }

  @Override
  public boolean claimRetry(UUID renderId, UUID userId, Instant now) {
    return jdbcTemplate.update(
            "UPDATE clip_renders SET status = 'QUEUED', progress = 0, output_object_key = NULL, "
                + "thumbnail_object_key = NULL, output_width = NULL, output_height = NULL, "
                + "output_duration_seconds = NULL, output_aspect_ratio = NULL, error_code = NULL, "
                + "error_message = NULL, updated_at = ? WHERE id = ? AND user_id = ? AND status = 'FAILED'",
            Timestamp.from(now),
            renderId,
            userId)
        == 1;
  }

  private Optional<ClipRender> query(String filter, Object... arguments) {
    return jdbcTemplate
        .query(
            "SELECT * FROM clip_renders " + filter,
            (resultSet, rowNumber) -> map(resultSet),
            arguments)
        .stream()
        .findFirst();
  }

  private static ClipRender map(ResultSet resultSet) throws SQLException {
    return new ClipRender(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("clip_id", UUID.class),
        resultSet.getObject("user_id", UUID.class),
        resultSet.getObject("project_id", UUID.class),
        resultSet.getInt("edit_version"),
        RenderStatus.valueOf(resultSet.getString("status")),
        resultSet.getInt("progress"),
        resultSet.getString("output_object_key"),
        resultSet.getString("thumbnail_object_key"),
        nullableInteger(resultSet, "output_width"),
        nullableInteger(resultSet, "output_height"),
        resultSet.getBigDecimal("output_duration_seconds"),
        nullableAspectRatio(resultSet.getString("output_aspect_ratio")),
        resultSet.getString("error_code"),
        resultSet.getString("error_message"),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant());
  }

  private static String value(AspectRatio aspectRatio) {
    return aspectRatio == null ? null : aspectRatio.value();
  }

  private static AspectRatio nullableAspectRatio(String value) {
    return value == null ? null : AspectRatio.fromValue(value);
  }

  private static Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
    int value = resultSet.getInt(column);
    return resultSet.wasNull() ? null : value;
  }
}
