package com.vcut.api.usage.infrastructure;

import com.vcut.api.usage.application.RetentionRepository;
import com.vcut.api.usage.domain.RetainedObject;
import com.vcut.api.usage.domain.RetentionAssetType;
import com.vcut.api.usage.domain.RetentionStatus;
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
public class JdbcRetentionRepository implements RetentionRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcRetentionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public RetainedObject register(RetainedObject retainedObject) {
    jdbcTemplate.update(
        "INSERT INTO retained_objects (id, user_id, project_id, object_key, asset_type, "
            + "size_bytes, retention_status, expires_at, delete_attempts, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, 'RETAINED', ?, 0, ?) "
            + "ON CONFLICT (object_key) DO UPDATE SET size_bytes = EXCLUDED.size_bytes, "
            + "asset_type = EXCLUDED.asset_type, retention_status = 'RETAINED', "
            + "expires_at = EXCLUDED.expires_at, delete_attempts = 0, last_failure_code = NULL, "
            + "claimed_at = NULL, deleted_at = NULL",
        retainedObject.id(),
        retainedObject.userId(),
        retainedObject.projectId(),
        retainedObject.objectKey(),
        retainedObject.assetType().name(),
        retainedObject.sizeBytes(),
        Timestamp.from(retainedObject.expiresAt()),
        Timestamp.from(retainedObject.createdAt()));
    return findByKey(retainedObject.objectKey()).orElseThrow();
  }

  @Override
  public Optional<RetainedObject> findByKey(String objectKey) {
    return jdbcTemplate
        .query(
            "SELECT * FROM retained_objects WHERE object_key = ?",
            JdbcRetentionRepository::map,
            objectKey)
        .stream()
        .findFirst();
  }

  @Override
  public List<RetainedObject> findRetainedForUser(UUID userId) {
    return jdbcTemplate.query(
        "SELECT * FROM retained_objects WHERE user_id = ? AND retention_status = 'RETAINED'",
        JdbcRetentionRepository::map,
        userId);
  }

  @Override
  public void updateExpiration(UUID id, Instant expiresAt) {
    jdbcTemplate.update(
        "UPDATE retained_objects SET expires_at = ? WHERE id = ? AND retention_status = 'RETAINED'",
        Timestamp.from(expiresAt),
        id);
  }

  @Override
  public void updateSize(String objectKey, long sizeBytes) {
    jdbcTemplate.update(
        "UPDATE retained_objects SET size_bytes = ? WHERE object_key = ? "
            + "AND retention_status <> 'DELETED'",
        sizeBytes,
        objectKey);
  }

  @Override
  public void markDeleted(String objectKey, Instant deletedAt) {
    jdbcTemplate.update(
        "UPDATE retained_objects SET retention_status = 'DELETED', deleted_at = ?, "
            + "last_failure_code = NULL, claimed_at = NULL WHERE object_key = ?",
        Timestamp.from(deletedAt),
        objectKey);
  }

  @Override
  public List<RetainedObject> claimExpired(Instant now, int limit) {
    return jdbcTemplate.query(
        "WITH candidates AS (SELECT id FROM retained_objects WHERE "
            + "(retention_status = 'RETAINED' AND expires_at <= ?) "
            + "OR (retention_status = 'DELETE_PENDING' AND last_failure_code IS NOT NULL "
            + "AND (claimed_at IS NULL OR claimed_at <= ? - interval '5 minutes')) "
            + "ORDER BY expires_at FOR UPDATE SKIP LOCKED LIMIT ?) "
            + "UPDATE retained_objects item SET retention_status = 'DELETE_PENDING', "
            + "delete_attempts = delete_attempts + 1, last_failure_code = NULL, claimed_at = ? "
            + "FROM candidates WHERE item.id = candidates.id RETURNING item.*",
        JdbcRetentionRepository::map,
        Timestamp.from(now),
        Timestamp.from(now),
        limit,
        Timestamp.from(now));
  }

  @Override
  public List<RetainedObject> findExpired(Instant now, int limit) {
    return jdbcTemplate.query(
        "SELECT * FROM retained_objects WHERE "
            + "(retention_status = 'RETAINED' AND expires_at <= ?) "
            + "OR (retention_status = 'DELETE_PENDING' AND last_failure_code IS NOT NULL "
            + "AND (claimed_at IS NULL OR claimed_at <= ? - interval '5 minutes')) "
            + "ORDER BY expires_at LIMIT ?",
        JdbcRetentionRepository::map,
        Timestamp.from(now),
        Timestamp.from(now),
        limit);
  }

  @Override
  public void markDeleted(UUID id, Instant deletedAt) {
    jdbcTemplate.update(
        "UPDATE retained_objects SET retention_status = 'DELETED', deleted_at = ?, "
            + "last_failure_code = NULL, claimed_at = NULL WHERE id = ? AND retention_status <> 'DELETED'",
        Timestamp.from(deletedAt),
        id);
  }

  @Override
  public void recordDeleteFailure(UUID id, String failureCode) {
    jdbcTemplate.update(
        "UPDATE retained_objects SET retention_status = 'DELETE_PENDING', "
            + "last_failure_code = ?, claimed_at = now() "
            + "WHERE id = ? AND retention_status = 'DELETE_PENDING'",
        failureCode,
        id);
  }

  private static RetainedObject map(ResultSet resultSet, int rowNumber) throws SQLException {
    return new RetainedObject(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("user_id", UUID.class),
        resultSet.getObject("project_id", UUID.class),
        resultSet.getString("object_key"),
        RetentionAssetType.valueOf(resultSet.getString("asset_type")),
        resultSet.getLong("size_bytes"),
        RetentionStatus.valueOf(resultSet.getString("retention_status")),
        resultSet.getTimestamp("expires_at").toInstant(),
        resultSet.getInt("delete_attempts"),
        resultSet.getString("last_failure_code"),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("deleted_at") == null
            ? null
            : resultSet.getTimestamp("deleted_at").toInstant());
  }
}
