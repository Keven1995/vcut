package com.vcut.api.job.infrastructure;

import com.vcut.api.job.application.OutboxRepository;
import com.vcut.api.job.domain.OutboxMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOutboxRepository implements OutboxRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcOutboxRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public OutboxMessage save(OutboxMessage message) {
    jdbcTemplate.update(
        "INSERT INTO outbox_messages (id, aggregate_type, aggregate_id, event_type, routing_key, payload, attempt, available_at, published_at, last_error, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        message.id(),
        message.aggregateType(),
        message.aggregateId(),
        message.eventType(),
        message.routingKey(),
        message.payload(),
        message.attempt(),
        Timestamp.from(message.availableAt()),
        timestamp(message.publishedAt()),
        message.lastError(),
        Timestamp.from(message.createdAt()));
    return message;
  }

  @Override
  public List<OutboxMessage> findPending(int limit, Instant now) {
    return jdbcTemplate.query(
        "SELECT * FROM outbox_messages WHERE published_at IS NULL AND available_at <= ? "
            + "ORDER BY created_at, id LIMIT ?",
        JdbcOutboxRepository::mapMessage,
        Timestamp.from(now),
        limit);
  }

  @Override
  public void markAttempt(UUID messageId, int attempt, Instant availableAt, String error) {
    jdbcTemplate.update(
        "UPDATE outbox_messages SET attempt = ?, available_at = ?, last_error = ? WHERE id = ? AND published_at IS NULL",
        attempt,
        Timestamp.from(availableAt),
        error,
        messageId);
  }

  @Override
  public void markPublished(UUID messageId, Instant publishedAt) {
    jdbcTemplate.update(
        "UPDATE outbox_messages SET published_at = ?, last_error = NULL WHERE id = ?",
        Timestamp.from(publishedAt),
        messageId);
  }

  private static OutboxMessage mapMessage(ResultSet resultSet, int rowNumber) throws SQLException {
    return new OutboxMessage(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("aggregate_type"),
        resultSet.getObject("aggregate_id", UUID.class),
        resultSet.getString("event_type"),
        resultSet.getString("routing_key"),
        resultSet.getString("payload"),
        resultSet.getInt("attempt"),
        resultSet.getTimestamp("available_at").toInstant(),
        nullableInstant(resultSet, "published_at"),
        resultSet.getString("last_error"),
        resultSet.getTimestamp("created_at").toInstant());
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
    Timestamp timestamp = resultSet.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }
}
