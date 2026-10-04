package com.vcut.api.clip.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vcut.api.clip.application.PublicationMetadataRepository;
import com.vcut.api.clip.domain.PublicationMetadata;
import com.vcut.api.clip.domain.PublicationMetadataStatus;
import com.vcut.api.clip.domain.PublicationPlatform;
import com.vcut.api.shared.errors.ProcessingException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPublicationMetadataRepository implements PublicationMetadataRepository {

  private static final TypeReference<List<String>> HASHTAGS_TYPE = new TypeReference<>() {};

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public JdbcPublicationMetadataRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  public Optional<PublicationMetadata> find(
      UUID clipId, int editVersion, PublicationPlatform platform) {
    return jdbcTemplate
        .query(
            "SELECT * FROM clip_publication_metadata "
                + "WHERE clip_id = ? AND edit_version = ? AND platform = ?",
            this::map,
            clipId,
            editVersion,
            platform.name())
        .stream()
        .findFirst();
  }

  @Override
  public List<PublicationMetadata> findAll(UUID clipId, int editVersion) {
    return jdbcTemplate.query(
        "SELECT * FROM clip_publication_metadata "
            + "WHERE clip_id = ? AND edit_version = ? ORDER BY platform",
        this::map,
        clipId,
        editVersion);
  }

  @Override
  public PublicationMetadata save(PublicationMetadata metadata) {
    jdbcTemplate.update(
        "INSERT INTO clip_publication_metadata (id, clip_id, user_id, edit_version, platform, "
            + "title, description, hashtags_json, status, created_at, updated_at, reviewed_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        values(metadata));
    return metadata;
  }

  @Override
  public PublicationMetadata update(PublicationMetadata metadata) {
    int updated =
        jdbcTemplate.update(
            "UPDATE clip_publication_metadata SET title = ?, description = ?, hashtags_json = ?, "
                + "status = ?, updated_at = ?, reviewed_at = ? WHERE id = ? AND clip_id = ? "
                + "AND edit_version = ?",
            metadata.title(),
            metadata.description(),
            serializeHashtags(metadata.hashtags()),
            metadata.status().name(),
            Timestamp.from(metadata.updatedAt()),
            timestamp(metadata.reviewedAt()),
            metadata.id(),
            metadata.clipId(),
            metadata.editVersion());
    if (updated != 1) {
      throw new IllegalArgumentException("publication metadata version changed during update");
    }
    return metadata;
  }

  private Object[] values(PublicationMetadata metadata) {
    return new Object[] {
      metadata.id(),
      metadata.clipId(),
      metadata.userId(),
      metadata.editVersion(),
      metadata.platform().name(),
      metadata.title(),
      metadata.description(),
      serializeHashtags(metadata.hashtags()),
      metadata.status().name(),
      Timestamp.from(metadata.createdAt()),
      Timestamp.from(metadata.updatedAt()),
      timestamp(metadata.reviewedAt())
    };
  }

  private PublicationMetadata map(ResultSet resultSet, int rowNumber) throws SQLException {
    try {
      return new PublicationMetadata(
          resultSet.getObject("id", UUID.class),
          resultSet.getObject("clip_id", UUID.class),
          resultSet.getObject("user_id", UUID.class),
          resultSet.getInt("edit_version"),
          PublicationPlatform.valueOf(resultSet.getString("platform")),
          resultSet.getString("title"),
          resultSet.getString("description"),
          objectMapper.readValue(resultSet.getString("hashtags_json"), HASHTAGS_TYPE),
          PublicationMetadataStatus.valueOf(resultSet.getString("status")),
          resultSet.getTimestamp("created_at").toInstant(),
          resultSet.getTimestamp("updated_at").toInstant(),
          resultSet.getTimestamp("reviewed_at") == null
              ? null
              : resultSet.getTimestamp("reviewed_at").toInstant());
    } catch (JsonProcessingException exception) {
      throw new SQLException("Stored publication hashtags are invalid.", exception);
    }
  }

  private String serializeHashtags(List<String> hashtags) {
    try {
      return objectMapper.writeValueAsString(hashtags);
    } catch (JsonProcessingException exception) {
      throw new ProcessingException("Could not serialize publication hashtags.");
    }
  }

  private static Timestamp timestamp(java.time.Instant value) {
    return value == null ? null : Timestamp.from(value);
  }
}
