package com.vcut.api.video.infrastructure;

import com.vcut.api.video.application.VideoRepository;
import com.vcut.api.video.domain.Video;
import com.vcut.api.video.domain.VideoUploadStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcVideoRepository implements VideoRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcVideoRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Video save(Video video) {
    jdbcTemplate.update(
        "INSERT INTO videos (id, user_id, project_id, object_key, original_filename, "
            + "declared_content_type, declared_size_bytes, actual_size_bytes, checksum_sha256, "
            + "duration_seconds, width, height, frame_rate, has_audio, upload_status, failure_code, "
            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        values(video));
    return video;
  }

  @Override
  public Optional<Video> findByIdForUser(UUID videoId, UUID userId) {
    return jdbcTemplate
        .query(
            "SELECT * FROM videos WHERE id = ? AND user_id = ?",
            JdbcVideoRepository::mapVideo,
            videoId,
            userId)
        .stream()
        .findFirst();
  }

  @Override
  public boolean updateIfStatus(Video video, VideoUploadStatus expectedStatus) {
    int updated =
        jdbcTemplate.update(
            "UPDATE videos SET object_key = ?, original_filename = ?, declared_content_type = ?, "
                + "declared_size_bytes = ?, actual_size_bytes = ?, checksum_sha256 = ?, "
                + "duration_seconds = ?, width = ?, height = ?, frame_rate = ?, has_audio = ?, "
                + "upload_status = ?, failure_code = ?, updated_at = ? "
                + "WHERE id = ? AND user_id = ? AND upload_status = ?",
            video.objectKey(),
            video.originalFilename(),
            video.declaredContentType(),
            video.declaredSizeBytes(),
            video.actualSizeBytes(),
            video.checksumSha256(),
            video.durationSeconds(),
            video.width(),
            video.height(),
            video.frameRate(),
            video.hasAudio(),
            video.status().name(),
            video.failureCode(),
            Timestamp.from(video.updatedAt()),
            video.id(),
            video.userId(),
            expectedStatus.name());
    return updated == 1;
  }

  @Override
  public void delete(UUID videoId, UUID userId) {
    jdbcTemplate.update("DELETE FROM videos WHERE id = ? AND user_id = ?", videoId, userId);
  }

  private static Object[] values(Video video) {
    return new Object[] {
      video.id(),
      video.userId(),
      video.projectId(),
      video.objectKey(),
      video.originalFilename(),
      video.declaredContentType(),
      video.declaredSizeBytes(),
      video.actualSizeBytes(),
      video.checksumSha256(),
      video.durationSeconds(),
      video.width(),
      video.height(),
      video.frameRate(),
      video.hasAudio(),
      video.status().name(),
      video.failureCode(),
      Timestamp.from(video.createdAt()),
      Timestamp.from(video.updatedAt())
    };
  }

  private static Video mapVideo(ResultSet resultSet, int rowNumber) throws SQLException {
    return new Video(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("user_id", UUID.class),
        resultSet.getObject("project_id", UUID.class),
        resultSet.getString("object_key"),
        resultSet.getString("original_filename"),
        resultSet.getString("declared_content_type"),
        resultSet.getLong("declared_size_bytes"),
        nullableLong(resultSet, "actual_size_bytes"),
        resultSet.getString("checksum_sha256"),
        resultSet.getBigDecimal("duration_seconds"),
        nullableInteger(resultSet, "width"),
        nullableInteger(resultSet, "height"),
        resultSet.getBigDecimal("frame_rate"),
        nullableBoolean(resultSet, "has_audio"),
        VideoUploadStatus.valueOf(resultSet.getString("upload_status")),
        resultSet.getString("failure_code"),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant());
  }

  private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
    long value = resultSet.getLong(column);
    return resultSet.wasNull() ? null : value;
  }

  private static Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
    int value = resultSet.getInt(column);
    return resultSet.wasNull() ? null : value;
  }

  private static Boolean nullableBoolean(ResultSet resultSet, String column) throws SQLException {
    boolean value = resultSet.getBoolean(column);
    return resultSet.wasNull() ? null : value;
  }
}
