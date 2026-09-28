package com.vcut.api.project.infrastructure;

import com.vcut.api.project.application.ProjectRepository;
import com.vcut.api.project.domain.Project;
import com.vcut.api.project.domain.ProjectStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProjectRepository implements ProjectRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcProjectRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Project save(Project project) {
    jdbcTemplate.update(
        "INSERT INTO projects (id, user_id, name, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
        project.id(),
        project.userId(),
        project.name(),
        project.status().name(),
        Timestamp.from(project.createdAt()),
        Timestamp.from(project.updatedAt()));
    return project;
  }

  @Override
  public Optional<Project> findByIdForUser(UUID projectId, UUID userId) {
    return jdbcTemplate
        .query(
            "SELECT * FROM projects WHERE id = ? AND user_id = ?",
            JdbcProjectRepository::mapProject,
            projectId,
            userId)
        .stream()
        .findFirst();
  }

  @Override
  public List<Project> findPage(UUID userId, int offset, int limit) {
    return jdbcTemplate.query(
        "SELECT * FROM projects WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
        JdbcProjectRepository::mapProject,
        userId,
        limit,
        offset);
  }

  @Override
  public long countByUserId(UUID userId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM projects WHERE user_id = ?", Long.class, userId);
    return count == null ? 0 : count;
  }

  @Override
  public Project update(Project project) {
    jdbcTemplate.update(
        "UPDATE projects SET name = ?, status = ?, updated_at = ? WHERE id = ? AND user_id = ?",
        project.name(),
        project.status().name(),
        Timestamp.from(project.updatedAt()),
        project.id(),
        project.userId());
    return project;
  }

  @Override
  public void delete(UUID projectId, UUID userId) {
    jdbcTemplate.update("DELETE FROM projects WHERE id = ? AND user_id = ?", projectId, userId);
  }

  private static Project mapProject(ResultSet resultSet, int rowNumber) throws SQLException {
    return new Project(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("user_id", UUID.class),
        resultSet.getString("name"),
        ProjectStatus.valueOf(resultSet.getString("status")),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant());
  }
}
