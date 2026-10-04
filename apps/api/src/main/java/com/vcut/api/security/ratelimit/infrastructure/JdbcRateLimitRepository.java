package com.vcut.api.security.ratelimit.infrastructure;

import com.vcut.api.security.ratelimit.application.RateLimitRepository;
import com.vcut.api.security.ratelimit.domain.RateLimitBucket;
import com.vcut.api.security.ratelimit.domain.RateLimitSubjectScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRateLimitRepository implements RateLimitRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcRateLimitRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public RateLimitBucket lockOrCreate(RateLimitBucket initial) {
    jdbcTemplate.update(
        "INSERT INTO rate_limit_buckets (subject_scope, subject_hash, operation, window_started_at, "
            + "request_count, violation_count, blocked_until, last_violation_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (subject_scope, subject_hash, operation) DO NOTHING",
        initial.scope().name(),
        initial.subjectHash(),
        initial.operation(),
        Timestamp.from(initial.windowStartedAt()),
        initial.requestCount(),
        initial.violationCount(),
        timestamp(initial.blockedUntil()),
        timestamp(initial.lastViolationAt()),
        Timestamp.from(initial.updatedAt()));
    return jdbcTemplate
        .query(
            "SELECT * FROM rate_limit_buckets WHERE subject_scope = ? AND subject_hash = ? "
                + "AND operation = ? FOR UPDATE",
            JdbcRateLimitRepository::map,
            initial.scope().name(),
            initial.subjectHash(),
            initial.operation())
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("rate limit bucket disappeared"));
  }

  @Override
  public void update(RateLimitBucket bucket) {
    int updated =
        jdbcTemplate.update(
            "UPDATE rate_limit_buckets SET window_started_at = ?, request_count = ?, "
                + "violation_count = ?, blocked_until = ?, last_violation_at = ?, updated_at = ? "
                + "WHERE subject_scope = ? AND subject_hash = ? AND operation = ?",
            Timestamp.from(bucket.windowStartedAt()),
            bucket.requestCount(),
            bucket.violationCount(),
            timestamp(bucket.blockedUntil()),
            timestamp(bucket.lastViolationAt()),
            Timestamp.from(bucket.updatedAt()),
            bucket.scope().name(),
            bucket.subjectHash(),
            bucket.operation());
    if (updated != 1) {
      throw new IllegalArgumentException("rate limit bucket not found");
    }
  }

  @Override
  public int deleteUpdatedBefore(Instant cutoff) {
    return jdbcTemplate.update(
        "DELETE FROM rate_limit_buckets WHERE updated_at < ?", Timestamp.from(cutoff));
  }

  private static RateLimitBucket map(ResultSet resultSet, int rowNumber) throws SQLException {
    return new RateLimitBucket(
        RateLimitSubjectScope.valueOf(resultSet.getString("subject_scope")),
        resultSet.getString("subject_hash"),
        resultSet.getString("operation"),
        resultSet.getTimestamp("window_started_at").toInstant(),
        resultSet.getInt("request_count"),
        resultSet.getInt("violation_count"),
        instant(resultSet, "blocked_until"),
        instant(resultSet, "last_violation_at"),
        resultSet.getTimestamp("updated_at").toInstant());
  }

  private static Instant instant(ResultSet resultSet, String column) throws SQLException {
    Timestamp value = resultSet.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }
}
