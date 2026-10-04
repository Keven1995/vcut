package com.vcut.api.usage.infrastructure;

import com.vcut.api.usage.application.SubscriptionRepository;
import com.vcut.api.usage.domain.PlanCode;
import com.vcut.api.usage.domain.Subscription;
import com.vcut.api.usage.domain.SubscriptionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSubscriptionRepository implements SubscriptionRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcSubscriptionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Subscription save(Subscription subscription) {
    jdbcTemplate.update(
        "INSERT INTO subscriptions (id, user_id, plan_code, status, period_start, period_end, "
            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        subscription.id(),
        subscription.userId(),
        subscription.planCode().name(),
        subscription.status().name(),
        Timestamp.from(subscription.periodStart()),
        Timestamp.from(subscription.periodEnd()),
        Timestamp.from(subscription.createdAt()),
        Timestamp.from(subscription.updatedAt()));
    return subscription;
  }

  @Override
  public Optional<Subscription> findActiveForUser(UUID userId, java.time.Instant at) {
    return jdbcTemplate
        .query(
            "SELECT * FROM subscriptions WHERE user_id = ? AND status = 'ACTIVE' "
                + "AND period_start <= ? AND period_end > ? ORDER BY period_end DESC LIMIT 1",
            JdbcSubscriptionRepository::map,
            userId,
            java.sql.Timestamp.from(at),
            java.sql.Timestamp.from(at))
        .stream()
        .findFirst();
  }

  private static Subscription map(ResultSet resultSet, int rowNumber) throws SQLException {
    return new Subscription(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("user_id", UUID.class),
        PlanCode.valueOf(resultSet.getString("plan_code")),
        SubscriptionStatus.valueOf(resultSet.getString("status")),
        resultSet.getTimestamp("period_start").toInstant(),
        resultSet.getTimestamp("period_end").toInstant(),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant());
  }
}
