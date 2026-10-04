package com.vcut.api.video.infrastructure;

import com.vcut.api.video.application.VideoImportDeadLetterRepository;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcVideoImportDeadLetterRepository implements VideoImportDeadLetterRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcVideoImportDeadLetterRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void record(UUID importId, String providerId, String failureCode, int attempts) {
    jdbcTemplate.update(
        "INSERT INTO video_import_dead_letters "
            + "(import_id, provider_id, failure_code, attempts, created_at) "
            + "VALUES (?, ?, ?, ?, now()) "
            + "ON CONFLICT (import_id) DO UPDATE SET failure_code = EXCLUDED.failure_code, "
            + "attempts = EXCLUDED.attempts, created_at = EXCLUDED.created_at",
        importId,
        providerId,
        failureCode,
        attempts);
  }
}
