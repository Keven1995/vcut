package com.vcut.api.auth.infrastructure;

import com.vcut.api.auth.application.UserRepository;
import com.vcut.api.auth.domain.User;
import com.vcut.api.auth.domain.UserStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUserRepository implements UserRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcUserRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<User> findById(UUID id) {
    return queryOne("SELECT * FROM users WHERE id = ?", id);
  }

  @Override
  public Optional<User> findByNormalizedEmail(String normalizedEmail) {
    return queryOne("SELECT * FROM users WHERE normalized_email = ?", normalizedEmail);
  }

  @Override
  public User save(User user) {
    jdbcTemplate.update(
        "INSERT INTO users (id, email, normalized_email, password_hash, status, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
        user.id(),
        user.email(),
        user.normalizedEmail(),
        user.passwordHash(),
        user.status().name(),
        Timestamp.from(user.createdAt()),
        Timestamp.from(user.updatedAt()));
    return user;
  }

  @Override
  public void touch(UUID userId, Instant updatedAt) {
    jdbcTemplate.update(
        "UPDATE users SET updated_at = ? WHERE id = ?", Timestamp.from(updatedAt), userId);
  }

  private Optional<User> queryOne(String sql, Object parameter) {
    return jdbcTemplate.query(sql, JdbcUserRepository::mapUser, parameter).stream().findFirst();
  }

  private static User mapUser(ResultSet resultSet, int rowNumber) throws SQLException {
    return new User(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("email"),
        resultSet.getString("normalized_email"),
        resultSet.getString("password_hash"),
        UserStatus.valueOf(resultSet.getString("status")),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant());
  }
}
