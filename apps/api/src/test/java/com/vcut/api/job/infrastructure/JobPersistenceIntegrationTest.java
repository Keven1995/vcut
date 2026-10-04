package com.vcut.api.job.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.vcut.api.shared.errors.QuotaExceededException;
import com.vcut.api.subscription.domain.BillingEventRecord;
import com.vcut.api.subscription.domain.BillingEventStatus;
import com.vcut.api.subscription.domain.BillingEventType;
import com.vcut.api.subscription.domain.BillingLedgerEntry;
import com.vcut.api.subscription.domain.BillingLedgerType;
import com.vcut.api.subscription.domain.CheckoutSession;
import com.vcut.api.subscription.domain.CheckoutStatus;
import com.vcut.api.subscription.domain.PaidSubscription;
import com.vcut.api.subscription.domain.SubscriptionPeriod;
import com.vcut.api.subscription.domain.SubscriptionStatus;
import com.vcut.api.subscription.infrastructure.JdbcSubscriptionPaymentRepository;
import com.vcut.api.usage.application.PlanLimits;
import com.vcut.api.usage.application.PlanLimitsProvider;
import com.vcut.api.usage.application.UsageApplicationService;
import com.vcut.api.usage.domain.PlanCode;
import com.vcut.api.usage.domain.RetentionPolicy;
import com.vcut.api.usage.domain.UsageCostRates;
import com.vcut.api.usage.domain.UsageMetrics;
import com.vcut.api.usage.infrastructure.JdbcSubscriptionRepository;
import com.vcut.api.usage.infrastructure.JdbcUsageRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JobPersistenceIntegrationTest {

  @Container
  static final GenericContainer<?> POSTGRES =
      new GenericContainer<>("postgres:16.6-alpine")
          .withEnv("POSTGRES_USER", "test")
          .withEnv("POSTGRES_PASSWORD", "test")
          .withEnv("POSTGRES_DB", "test")
          .withExposedPorts(5432)
          .waitingFor(Wait.forListeningPort());

  private static JdbcTemplate jdbc;
  private static DriverManagerDataSource dataSource;

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(jdbcUrl(), "test", "test")
        .locations("classpath:db/migration")
        .load()
        .migrate();
    dataSource = new DriverManagerDataSource(jdbcUrl());
    dataSource.setUsername("test");
    dataSource.setPassword("test");
    jdbc = new JdbcTemplate(dataSource);
  }

  private static String jdbcUrl() {
    return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/test";
  }

  @Test
  void persistsCorrelatedPipelineJobStageAndOutboxRecords() {
    UUID userId = UUID.randomUUID();
    UUID projectId = UUID.randomUUID();
    UUID videoId = UUID.randomUUID();
    UUID pipelineId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    UUID stageId = UUID.randomUUID();
    UUID outboxId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    Timestamp now = Timestamp.from(java.time.Instant.parse("2026-09-30T12:00:00Z"));

    jdbc.update(
        "INSERT INTO users (id, email, normalized_email, password_hash, status, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
        userId,
        userId + "@example.test",
        userId + "@example.test",
        "test-hash",
        now,
        now);
    jdbc.update(
        "INSERT INTO projects (id, user_id, name, status, created_at, updated_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
        projectId,
        userId,
        "Persistence test",
        now,
        now);
    jdbc.update(
        "INSERT INTO videos (id, user_id, project_id, object_key, original_filename, declared_content_type, declared_size_bytes, upload_status, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, 'UPLOADED', ?, ?)",
        videoId,
        userId,
        projectId,
        "users/" + userId + "/projects/" + projectId + "/source/" + videoId + "/original.mp4",
        "fixture.mp4",
        "video/mp4",
        100L,
        now,
        now);
    jdbc.update(
        "INSERT INTO pipelines (id, user_id, project_id, video_id, version, status, correlation_id, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, 1, 'QUEUED', ?, ?, ?)",
        pipelineId,
        userId,
        projectId,
        videoId,
        correlationId,
        now,
        now);
    jdbc.update(
        "INSERT INTO jobs (id, pipeline_id, user_id, project_id, video_id, operation, version, idempotency_key, status, current_stage, attempt, progress, correlation_id, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, 'VIDEO_VALIDATION', 1, ?, 'QUEUED', 'INGEST', 1, 0, ?, ?, ?)",
        jobId,
        pipelineId,
        userId,
        projectId,
        videoId,
        videoId + ":VIDEO_VALIDATION:1",
        correlationId,
        now,
        now);
    jdbc.update(
        "INSERT INTO stage_runs (id, job_id, pipeline_version, stage_name, status, attempt, progress, input_payload, created_at, updated_at) "
            + "VALUES (?, ?, 1, 'INGEST', 'QUEUED', 1, 0, ?, ?, ?)",
        stageId,
        jobId,
        "{\"jobId\":\"" + jobId + "\"}",
        now,
        now);
    jdbc.update(
        "INSERT INTO outbox_messages (id, aggregate_type, aggregate_id, event_type, routing_key, payload, attempt, available_at, created_at) "
            + "VALUES (?, 'JOB', ?, 'VideoValidationRequested', 'pipeline.video.validate', ?, 0, ?, ?)",
        outboxId,
        jobId,
        "{\"jobId\":\"" + jobId + "\",\"correlationId\":\"" + correlationId + "\"}",
        now,
        now);

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT p.correlation_id, j.status AS job_status, s.status AS stage_status, s.pipeline_version, o.published_at "
                + "FROM pipelines p JOIN jobs j ON j.pipeline_id = p.id "
                + "JOIN stage_runs s ON s.job_id = j.id "
                + "JOIN outbox_messages o ON o.aggregate_id = j.id WHERE j.id = ?",
            jobId);

    assertThat(row.get("correlation_id")).isEqualTo(correlationId);
    assertThat(row.get("job_status")).isEqualTo("QUEUED");
    assertThat(row.get("stage_status")).isEqualTo("QUEUED");
    assertThat(row.get("pipeline_version")).isEqualTo(1);
    assertThat(row.get("published_at")).isNull();
  }

  @Test
  void concurrentQuotaReservationsCannotExceedTheMonthlyLimit() throws Exception {
    UUID userId = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.parse("2026-10-03T12:00:00Z"));
    jdbc.update(
        "INSERT INTO users (id, email, normalized_email, password_hash, status, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
        userId,
        userId + "@usage.example.test",
        userId + "@usage.example.test",
        "test-hash",
        now,
        now);
    PlanLimitsProvider limits =
        planCode ->
            new PlanLimits(
                BigDecimal.valueOf(60),
                100_000_000,
                7_200,
                1_000_000_000,
                3,
                1920,
                1080,
                0,
                new RetentionPolicy(30, 7, 3, 7, 30, 30, 7));
    UsageApplicationService usage =
        new UsageApplicationService(
            new JdbcUsageRepository(jdbc),
            new JdbcSubscriptionRepository(jdbc),
            limits,
            new UsageCostRates(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger succeeded = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();
    AtomicReference<UUID> winningResourceId = new AtomicReference<>();
    UUID firstResourceId = UUID.randomUUID();
    UUID secondResourceId = UUID.randomUUID();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () -> {
                reserveAfterBarrier(transaction, start, usage, userId, firstResourceId);
                return firstResourceId;
              });
      var second =
          executor.submit(
              () -> {
                reserveAfterBarrier(transaction, start, usage, userId, secondResourceId);
                return secondResourceId;
              });
      start.countDown();
      for (var future : List.of(first, second)) {
        try {
          winningResourceId.set(future.get(10, TimeUnit.SECONDS));
          succeeded.incrementAndGet();
        } catch (java.util.concurrent.ExecutionException exception) {
          if (exception.getCause() instanceof QuotaExceededException) {
            rejected.incrementAndGet();
          } else {
            throw exception;
          }
        }
      }
    } finally {
      executor.shutdownNow();
    }

    assertThat(succeeded.get()).isEqualTo(1);
    assertThat(rejected.get()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT reserved_minutes FROM usage_periods WHERE user_id = ?",
                BigDecimal.class,
                userId))
        .isEqualByComparingTo("40");
    transaction.executeWithoutResult(
        ignored ->
            usage.confirmProcessing(
                userId, winningResourceId.get(), "TRANSCRIPTION", 1, BigDecimal.valueOf(40 * 60L)));
    transaction.executeWithoutResult(
        ignored ->
            usage.confirmProcessing(
                userId, winningResourceId.get(), "TRANSCRIPTION", 1, BigDecimal.valueOf(40 * 60L)));
    assertThat(
            jdbc.queryForObject(
                "SELECT processed_minutes FROM usage_periods WHERE user_id = ?",
                BigDecimal.class,
                userId))
        .isEqualByComparingTo("40");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM usage_ledger_entries WHERE user_id = ? AND outcome = 'SUCCEEDED'",
                Integer.class,
                userId))
        .isEqualTo(1);
    transaction.executeWithoutResult(
        ignored -> {
          var metrics =
              new UsageMetrics(
                  BigDecimal.ZERO,
                  BigDecimal.valueOf(0.5),
                  500,
                  BigDecimal.valueOf(2),
                  BigDecimal.valueOf(0.25),
                  100,
                  1_024,
                  1);
          usage.recordMetrics(
              userId, winningResourceId.get(), "CLIP_ANALYSIS", 1, metrics, "SUCCEEDED");
          usage.recordMetrics(
              userId, winningResourceId.get(), "CLIP_ANALYSIS", 1, metrics, "SUCCEEDED");
        });
    assertThat(
            jdbc.queryForObject(
                "SELECT multimodal_minutes FROM usage_periods WHERE user_id = ?",
                BigDecimal.class,
                userId))
        .isEqualByComparingTo("0.5");
    assertThat(
            jdbc.queryForObject(
                "SELECT llm_tokens FROM usage_periods WHERE user_id = ?", Long.class, userId))
        .isEqualTo(500L);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM usage_ledger_entries WHERE user_id = ? AND operation = 'CLIP_ANALYSIS'",
                Integer.class,
                userId))
        .isEqualTo(1);
    transaction.executeWithoutResult(
        ignored -> usage.synchronizePlanSnapshot(userId, PlanCode.PRO));
    assertThat(
            jdbc.queryForObject(
                "SELECT plan_code FROM usage_periods WHERE user_id = ?", String.class, userId))
        .isEqualTo("PRO");
    assertThat(
            jdbc.queryForObject(
                "SELECT processed_minutes FROM usage_periods WHERE user_id = ?",
                BigDecimal.class,
                userId))
        .isEqualByComparingTo("40");

    UUID failedResourceId = UUID.randomUUID();
    transaction.executeWithoutResult(
        ignored ->
            usage.reserveProcessingMinutes(
                userId, failedResourceId, "TRANSCRIPTION", 2, BigDecimal.valueOf(10 * 60L)));
    transaction.executeWithoutResult(
        ignored ->
            usage.releaseProcessingReservation(userId, failedResourceId, "TRANSCRIPTION", 2));
    assertThat(
            jdbc.queryForObject(
                "SELECT reserved_minutes FROM usage_periods WHERE user_id = ?",
                BigDecimal.class,
                userId))
        .isEqualByComparingTo("0");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM usage_ledger_entries WHERE user_id = ? AND outcome = 'RELEASED'",
                Integer.class,
                userId))
        .isEqualTo(1);
  }

  @Test
  void persistsProviderCheckoutEventsAndReconcilesTheInternalBillingLedger() {
    UUID userId = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.parse("2026-10-04T12:00:00Z"));
    jdbc.update(
        "INSERT INTO users (id, email, normalized_email, password_hash, status, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
        userId,
        userId + "@billing.example.test",
        userId + "@billing.example.test",
        "test-hash",
        now,
        now);
    var repository = new JdbcSubscriptionPaymentRepository(jdbc);
    UUID checkoutId = UUID.randomUUID();
    Instant instant = now.toInstant();
    CheckoutSession pending =
        new CheckoutSession(
            checkoutId,
            userId,
            PlanCode.PRO,
            CheckoutStatus.PENDING,
            "sandbox",
            null,
            null,
            null,
            1_200,
            java.util.Currency.getInstance("USD"),
            instant.plusSeconds(1_800),
            instant,
            instant,
            null);
    repository.save(pending);
    CheckoutSession attached =
        pending.attachProviderDetails(
            "provider-checkout-id",
            "provider-customer-id",
            "https://checkout.example.test/session",
            pending.expiresAt(),
            instant.plusSeconds(1));
    repository.update(attached);
    UUID billingEventId = UUID.randomUUID();
    BillingEventRecord processingEvent =
        new BillingEventRecord(
            billingEventId,
            "sandbox",
            "provider-event-id",
            1,
            BillingEventType.SUBSCRIPTION_CREATED,
            BillingEventStatus.PROCESSING,
            instant.plusSeconds(2),
            null,
            null,
            null,
            1_200,
            java.util.Currency.getInstance("USD"),
            instant.plusSeconds(3),
            null);
    assertThat(repository.registerReceived(processingEvent)).isTrue();
    assertThat(repository.registerReceived(processingEvent)).isFalse();
    PaidSubscription subscription =
        new PaidSubscription(
            UUID.randomUUID(),
            userId,
            PlanCode.PRO,
            SubscriptionStatus.ACTIVE,
            "sandbox",
            "provider-customer-id",
            "provider-subscription-id",
            1_200,
            java.util.Currency.getInstance("USD"),
            new SubscriptionPeriod(instant, instant.plusSeconds(2_592_000)),
            false,
            instant.plusSeconds(2),
            instant.plusSeconds(2),
            instant.plusSeconds(2));
    repository.save(subscription);
    CheckoutSession completed = attached.complete(instant.plusSeconds(2));
    repository.update(completed);
    repository.completeEvent(
        new BillingEventRecord(
            billingEventId,
            "sandbox",
            "provider-event-id",
            1,
            BillingEventType.SUBSCRIPTION_CREATED,
            BillingEventStatus.APPLIED,
            instant.plusSeconds(2),
            userId,
            checkoutId,
            subscription.id(),
            1_200,
            java.util.Currency.getInstance("USD"),
            instant.plusSeconds(3),
            instant.plusSeconds(3)));
    repository.saveLedgerEntry(
        new BillingLedgerEntry(
            UUID.randomUUID(),
            userId,
            subscription.id(),
            billingEventId,
            BillingLedgerType.CHARGE,
            1_200,
            java.util.Currency.getInstance("USD"),
            instant.plusSeconds(2)));

    var reconciliation = repository.reconcileForUser(userId, java.util.Currency.getInstance("USD"));

    assertThat(repository.findLatestForUser(userId)).contains(subscription);
    assertThat(repository.findByProviderSessionIdForUpdate("provider-checkout-id"))
        .contains(completed);
    assertThat(reconciliation.appliedEventCount()).isEqualTo(1);
    assertThat(reconciliation.ledgerEntryCount()).isEqualTo(1);
    assertThat(reconciliation.discrepancyMinorUnits()).isZero();
  }

  private static void reserveAfterBarrier(
      TransactionTemplate transaction,
      CountDownLatch start,
      UsageApplicationService usage,
      UUID userId,
      UUID videoId)
      throws InterruptedException {
    start.await();
    transaction.executeWithoutResult(
        ignored ->
            usage.reserveProcessingMinutes(
                userId, videoId, "TRANSCRIPTION", 1, BigDecimal.valueOf(40 * 60L)));
  }
}
