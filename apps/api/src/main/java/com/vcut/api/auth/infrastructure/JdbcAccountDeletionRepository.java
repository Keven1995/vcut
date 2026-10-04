package com.vcut.api.auth.infrastructure;

import com.vcut.api.auth.application.AccountDeletionRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAccountDeletionRepository implements AccountDeletionRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcAccountDeletionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<Instant> findPendingDeleteAfter(UUID userId) {
    return jdbcTemplate
        .query(
            "SELECT delete_after FROM account_deletion_requests "
                + "WHERE user_id = ? AND status = 'PENDING'",
            (resultSet, rowNumber) -> resultSet.getTimestamp("delete_after").toInstant(),
            userId)
        .stream()
        .findFirst();
  }

  @Override
  public void enqueue(UUID userId, Instant requestedAt, Instant deleteAfter) {
    jdbcTemplate.update(
        "UPDATE jobs SET status = 'CANCELLED', error_code = 'ACCOUNT_DELETION_REQUESTED', "
            + "error_message = 'Account deletion was requested.', updated_at = ?, completed_at = ? "
            + "WHERE user_id = ? AND status IN ('QUEUED', 'PROCESSING')",
        Timestamp.from(requestedAt),
        Timestamp.from(requestedAt),
        userId);
    jdbcTemplate.update(
        "UPDATE pipelines SET status = 'CANCELLED', updated_at = ? "
            + "WHERE user_id = ? AND status IN ('QUEUED', 'PROCESSING')",
        Timestamp.from(requestedAt),
        userId);
    jdbcTemplate.update(
        "UPDATE stage_runs SET status = 'FAILED', error_code = 'ACCOUNT_DELETION_REQUESTED', "
            + "error_message = 'Account deletion was requested.', finished_at = ?, updated_at = ? "
            + "WHERE job_id IN (SELECT id FROM jobs WHERE user_id = ?) "
            + "AND status IN ('QUEUED', 'PROCESSING', 'RETRYING')",
        Timestamp.from(requestedAt),
        Timestamp.from(requestedAt),
        userId);
    jdbcTemplate.update(
        "DELETE FROM outbox_messages WHERE aggregate_type = 'JOB' "
            + "AND aggregate_id IN (SELECT id FROM jobs WHERE user_id = ?) "
            + "AND published_at IS NULL",
        userId);
    jdbcTemplate.update(
        "UPDATE subscriptions SET status = 'CANCELED', cancel_at_period_end = FALSE, "
            + "updated_at = ? WHERE user_id = ? AND status IN ('ACTIVE', 'PAST_DUE')",
        Timestamp.from(requestedAt),
        userId);
    jdbcTemplate.update(
        "INSERT INTO account_deletion_requests "
            + "(id, user_id, requested_at, delete_after, status, attempt_count) "
            + "VALUES (?, ?, ?, ?, 'PENDING', 0) "
            + "ON CONFLICT (user_id) WHERE user_id IS NOT NULL "
            + "AND status IN ('PENDING', 'PROCESSING') DO NOTHING",
        UUID.randomUUID(),
        userId,
        Timestamp.from(requestedAt),
        Timestamp.from(deleteAfter));
  }

  @Override
  public boolean cancelPending(UUID userId, Instant cancelledAt) {
    return jdbcTemplate.update(
            "UPDATE account_deletion_requests SET status = 'CANCELLED', completed_at = ?, "
                + "claimed_at = NULL WHERE user_id = ? AND status = 'PENDING' AND delete_after > ?",
            Timestamp.from(cancelledAt),
            userId,
            Timestamp.from(cancelledAt))
        == 1;
  }
}
