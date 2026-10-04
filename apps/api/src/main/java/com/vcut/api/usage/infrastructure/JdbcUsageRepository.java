package com.vcut.api.usage.infrastructure;

import com.vcut.api.usage.application.UsageRepository;
import com.vcut.api.usage.domain.PlanCode;
import com.vcut.api.usage.domain.QuotaReservation;
import com.vcut.api.usage.domain.QuotaReservationStatus;
import com.vcut.api.usage.domain.UsageLedgerEntry;
import com.vcut.api.usage.domain.UsagePeriod;
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
public class JdbcUsageRepository implements UsageRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcUsageRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<UsagePeriod> findPeriodForUpdate(UUID userId, Instant periodStart) {
    return jdbcTemplate
        .query(
            "SELECT * FROM usage_periods WHERE user_id = ? AND period_start = ? FOR UPDATE",
            JdbcUsageRepository::mapPeriod,
            userId,
            Timestamp.from(periodStart))
        .stream()
        .findFirst();
  }

  @Override
  public Optional<UsagePeriod> findPeriodByIdForUpdate(UUID periodId) {
    return jdbcTemplate
        .query(
            "SELECT * FROM usage_periods WHERE id = ? FOR UPDATE",
            JdbcUsageRepository::mapPeriod,
            periodId)
        .stream()
        .findFirst();
  }

  @Override
  public UsagePeriod lockOrCreatePeriod(UsagePeriod period) {
    jdbcTemplate.update(
        "INSERT INTO usage_periods (id, user_id, plan_code, period_start, period_end, "
            + "reserved_minutes, processed_minutes, stored_bytes, renders, transcription_minutes, "
            + "multimodal_minutes, "
            + "llm_tokens, cpu_seconds, gpu_seconds, bandwidth_bytes, estimated_cost, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, ?, ?) "
            + "ON CONFLICT (user_id, period_start) DO NOTHING",
        period.id(),
        period.userId(),
        period.planCode().name(),
        Timestamp.from(period.periodStart()),
        Timestamp.from(period.periodEnd()),
        Timestamp.from(period.createdAt()),
        Timestamp.from(period.updatedAt()));
    return findPeriodForUpdate(period.userId(), period.periodStart())
        .orElseThrow(() -> new IllegalStateException("usage period disappeared during creation"));
  }

  @Override
  public UsagePeriod savePeriod(UsagePeriod period) {
    return lockOrCreatePeriod(period);
  }

  @Override
  public Optional<QuotaReservation> findReservationForUpdate(String idempotencyKey) {
    return jdbcTemplate
        .query(
            "SELECT * FROM quota_reservations WHERE idempotency_key = ? FOR UPDATE",
            JdbcUsageRepository::mapReservation,
            idempotencyKey)
        .stream()
        .findFirst();
  }

  @Override
  public QuotaReservation saveReservation(QuotaReservation reservation) {
    jdbcTemplate.update(
        "INSERT INTO quota_reservations (id, usage_period_id, user_id, resource_id, operation, "
            + "idempotency_key, reserved_minutes, status, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        reservation.id(),
        reservation.periodId(),
        reservation.userId(),
        reservation.resourceId(),
        reservation.operation(),
        reservation.idempotencyKey(),
        reservation.reservedMinutes(),
        reservation.status().name(),
        Timestamp.from(reservation.createdAt()),
        Timestamp.from(reservation.updatedAt()));
    return reservation;
  }

  @Override
  public void updatePeriod(UsagePeriod period) {
    int updated =
        jdbcTemplate.update(
            "UPDATE usage_periods SET plan_code = ?, reserved_minutes = ?, processed_minutes = ?, "
                + "stored_bytes = ?, renders = ?, transcription_minutes = ?, multimodal_minutes = ?, "
                + "llm_tokens = ?, "
                + "cpu_seconds = ?, gpu_seconds = ?, bandwidth_bytes = ?, estimated_cost = ?, updated_at = ? "
                + "WHERE id = ?",
            period.planCode().name(),
            period.reservedMinutes(),
            period.processedMinutes(),
            period.storedBytes(),
            period.renders(),
            period.transcriptionMinutes(),
            period.multimodalMinutes(),
            period.llmTokens(),
            period.cpuSeconds(),
            period.gpuSeconds(),
            period.bandwidthBytes(),
            period.estimatedCost(),
            Timestamp.from(period.updatedAt()),
            period.id());
    if (updated != 1) {
      throw new IllegalArgumentException("usage period not found");
    }
  }

  @Override
  public void updateReservation(QuotaReservation reservation) {
    int updated =
        jdbcTemplate.update(
            "UPDATE quota_reservations SET status = ?, updated_at = ? WHERE id = ?",
            reservation.status().name(),
            Timestamp.from(reservation.updatedAt()),
            reservation.id());
    if (updated != 1) {
      throw new IllegalArgumentException("quota reservation not found");
    }
  }

  @Override
  public void saveLedgerEntry(UsageLedgerEntry entry) {
    jdbcTemplate.update(
        "INSERT INTO usage_ledger_entries (id, usage_period_id, user_id, resource_id, operation, "
            + "idempotency_key, reserved_minutes, processed_minutes, storage_bytes, renders, "
            + "transcription_minutes, multimodal_minutes, llm_tokens, cpu_seconds, gpu_seconds, bandwidth_bytes, "
            + "estimated_cost, outcome, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        entry.id(),
        entry.periodId(),
        entry.userId(),
        entry.resourceId(),
        entry.operation(),
        entry.idempotencyKey(),
        entry.reservedMinutes(),
        entry.processedMinutes(),
        entry.storageBytes(),
        entry.renders(),
        entry.transcriptionMinutes(),
        entry.multimodalMinutes(),
        entry.llmTokens(),
        entry.cpuSeconds(),
        entry.gpuSeconds(),
        entry.bandwidthBytes(),
        entry.estimatedCost(),
        entry.outcome(),
        Timestamp.from(entry.createdAt()));
  }

  @Override
  public Optional<UsageLedgerEntry> findLedgerByIdempotencyKey(String idempotencyKey) {
    return jdbcTemplate
        .query(
            "SELECT * FROM usage_ledger_entries WHERE idempotency_key = ?",
            JdbcUsageRepository::mapLedgerEntry,
            idempotencyKey)
        .stream()
        .findFirst();
  }

  @Override
  public List<UsageLedgerEntry> recentLedger(UUID userId, Instant periodStart, int limit) {
    return jdbcTemplate.query(
        "SELECT ledger.* FROM usage_ledger_entries ledger JOIN usage_periods period "
            + "ON period.id = ledger.usage_period_id WHERE ledger.user_id = ? AND period.period_start = ? "
            + "ORDER BY ledger.created_at DESC LIMIT ?",
        JdbcUsageRepository::mapLedgerEntry,
        userId,
        Timestamp.from(periodStart),
        limit);
  }

  @Override
  public long retainedBytes(UUID userId) {
    Long total =
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(size_bytes), 0) FROM retained_objects "
                + "WHERE user_id = ? AND retention_status <> 'DELETED'",
            Long.class,
            userId);
    return total == null ? 0 : total;
  }

  @Override
  public int countActiveJobs(UUID userId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT "
                + "(SELECT COUNT(*) FROM jobs WHERE user_id = ? AND status IN ('QUEUED', 'PROCESSING')) "
                + "+ (SELECT COUNT(*) FROM transcriptions WHERE user_id = ? "
                + "AND status IN ('QUEUED', 'PROCESSING', 'RETRYING')) "
                + "+ (SELECT COUNT(*) FROM clip_analysis_runs WHERE user_id = ? "
                + "AND status IN ('QUEUED', 'PROCESSING', 'RETRYING')) "
                + "+ (SELECT COUNT(*) FROM clips WHERE user_id = ? "
                + "AND status IN ('QUEUED', 'PROCESSING')) "
                + "+ (SELECT COUNT(*) FROM clip_renders WHERE user_id = ? "
                + "AND status IN ('QUEUED', 'PROCESSING'))",
            Integer.class,
            userId,
            userId,
            userId,
            userId,
            userId);
    return count == null ? 0 : count;
  }

  private static UsagePeriod mapPeriod(ResultSet resultSet, int rowNumber) throws SQLException {
    return new UsagePeriod(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("user_id", UUID.class),
        PlanCode.valueOf(resultSet.getString("plan_code")),
        resultSet.getTimestamp("period_start").toInstant(),
        resultSet.getTimestamp("period_end").toInstant(),
        resultSet.getBigDecimal("reserved_minutes"),
        resultSet.getBigDecimal("processed_minutes"),
        resultSet.getLong("stored_bytes"),
        resultSet.getLong("renders"),
        resultSet.getBigDecimal("transcription_minutes"),
        resultSet.getBigDecimal("multimodal_minutes"),
        resultSet.getLong("llm_tokens"),
        resultSet.getBigDecimal("cpu_seconds"),
        resultSet.getBigDecimal("gpu_seconds"),
        resultSet.getLong("bandwidth_bytes"),
        resultSet.getBigDecimal("estimated_cost"),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant());
  }

  private static QuotaReservation mapReservation(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new QuotaReservation(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("usage_period_id", UUID.class),
        resultSet.getObject("user_id", UUID.class),
        resultSet.getObject("resource_id", UUID.class),
        resultSet.getString("operation"),
        resultSet.getString("idempotency_key"),
        resultSet.getBigDecimal("reserved_minutes"),
        QuotaReservationStatus.valueOf(resultSet.getString("status")),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant());
  }

  private static UsageLedgerEntry mapLedgerEntry(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new UsageLedgerEntry(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("usage_period_id", UUID.class),
        resultSet.getObject("user_id", UUID.class),
        resultSet.getObject("resource_id", UUID.class),
        resultSet.getString("operation"),
        resultSet.getString("idempotency_key"),
        resultSet.getBigDecimal("reserved_minutes"),
        resultSet.getBigDecimal("processed_minutes"),
        resultSet.getLong("storage_bytes"),
        resultSet.getLong("renders"),
        resultSet.getBigDecimal("transcription_minutes"),
        resultSet.getBigDecimal("multimodal_minutes"),
        resultSet.getLong("llm_tokens"),
        resultSet.getBigDecimal("cpu_seconds"),
        resultSet.getBigDecimal("gpu_seconds"),
        resultSet.getLong("bandwidth_bytes"),
        resultSet.getBigDecimal("estimated_cost"),
        resultSet.getString("outcome"),
        resultSet.getTimestamp("created_at").toInstant());
  }
}
