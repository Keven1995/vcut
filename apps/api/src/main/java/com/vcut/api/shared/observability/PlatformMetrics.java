package com.vcut.api.shared.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PlatformMetrics implements MeterBinder {

  private static final List<String> JOB_STATUSES =
      List.of("QUEUED", "PROCESSING", "COMPLETED", "FAILED", "CANCELLED");

  private final JdbcTemplate jdbcTemplate;
  private final long storageCapacityBytes;

  public PlatformMetrics(
      JdbcTemplate jdbcTemplate,
      @Value("${vcut.storage.capacity-bytes:0}") long storageCapacityBytes) {
    this.jdbcTemplate = jdbcTemplate;
    this.storageCapacityBytes = storageCapacityBytes;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder(
            "vcut.outbox.pending",
            this,
            metrics -> metrics.count("outbox_messages", "published_at IS NULL"))
        .register(registry);
    Gauge.builder(
            "vcut.outbox.oldest_pending_seconds", this, PlatformMetrics::oldestPendingOutboxSeconds)
        .register(registry);
    for (String status : JOB_STATUSES) {
      Gauge.builder("vcut.jobs.status", this, metrics -> metrics.countJobs(status))
          .tag("status", status)
          .register(registry);
    }
    Gauge.builder(
            "vcut.import.dead_letters",
            this,
            metrics -> metrics.count("video_import_dead_letters", "TRUE"))
        .register(registry);
    Gauge.builder(
            "vcut.renders.failed",
            this,
            metrics -> metrics.count("clip_renders", "status = 'FAILED'"))
        .register(registry);
    Gauge.builder("vcut.storage.tracked_bytes", this, PlatformMetrics::trackedStorageBytes)
        .register(registry);
    Gauge.builder(
            "vcut.storage.retained_objects",
            this,
            metrics -> metrics.count("retained_objects", "retention_status <> 'DELETED'"))
        .register(registry);
    Gauge.builder(
            "vcut.storage.delete_pending_objects",
            this,
            metrics -> metrics.count("retained_objects", "retention_status = 'DELETE_PENDING'"))
        .register(registry);
    Gauge.builder(
            "vcut.storage.capacity_bytes", this, metrics -> (double) metrics.storageCapacityBytes)
        .register(registry);
    Gauge.builder(
            "vcut.storage.capacity_utilization",
            this,
            metrics -> metrics.storageCapacityUtilization())
        .register(registry);
  }

  private double countJobs(String status) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM jobs WHERE status = ?", Long.class, status);
    return count == null ? 0 : count.doubleValue();
  }

  private double count(String table, String condition) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE " + condition, Long.class);
    return count == null ? 0 : count.doubleValue();
  }

  private double trackedStorageBytes() {
    Long bytes =
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(size_bytes), 0) FROM retained_objects "
                + "WHERE retention_status <> 'DELETED'",
            Long.class);
    return bytes == null ? 0 : bytes.doubleValue();
  }

  private double oldestPendingOutboxSeconds() {
    Double seconds =
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at))), 0) "
                + "FROM outbox_messages WHERE published_at IS NULL",
            Double.class);
    return seconds == null ? 0 : seconds;
  }

  private double storageCapacityUtilization() {
    if (storageCapacityBytes < 1) {
      return -1;
    }
    return trackedStorageBytes() / storageCapacityBytes;
  }
}
