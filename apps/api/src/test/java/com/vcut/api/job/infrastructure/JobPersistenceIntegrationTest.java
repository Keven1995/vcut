package com.vcut.api.job.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.vcut.api.auth.infrastructure.JdbcAccountDeletionRepository;
import com.vcut.api.security.audit.domain.SecurityAuditEvent;
import com.vcut.api.security.audit.domain.SecurityAuditOutcome;
import com.vcut.api.security.audit.infrastructure.JdbcSecurityAuditRepository;
import com.vcut.api.security.ratelimit.domain.RateLimitBucket;
import com.vcut.api.security.ratelimit.domain.RateLimitSubjectScope;
import com.vcut.api.security.ratelimit.infrastructure.JdbcRateLimitRepository;
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
      new GenericContainer<>("postgres:16.14-alpine3.22")
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

  @Test
  void persistsHashedRateLimitStateAndExpiresSanitizedAuditRecords() {
    Instant now = Instant.parse("2026-10-04T12:00:00Z");
    var rateLimits = new JdbcRateLimitRepository(jdbc);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    String subjectHash = "a".repeat(64);
    transaction.executeWithoutResult(
        ignored -> {
          RateLimitBucket initial =
              RateLimitBucket.empty(RateLimitSubjectScope.IP, subjectHash, "auth-login", now, now);
          RateLimitBucket created = rateLimits.lockOrCreate(initial);
          RateLimitBucket.Attempt allowed =
              created.consume(
                  now,
                  now,
                  1,
                  java.time.Duration.ofSeconds(10),
                  java.time.Duration.ofMinutes(2),
                  java.time.Duration.ofHours(1));
          assertThat(allowed.allowed()).isTrue();
          rateLimits.update(allowed.bucket());
          RateLimitBucket.Attempt blocked =
              rateLimits
                  .lockOrCreate(initial)
                  .consume(
                      now.plusSeconds(1),
                      now,
                      1,
                      java.time.Duration.ofSeconds(10),
                      java.time.Duration.ofMinutes(2),
                      java.time.Duration.ofHours(1));
          assertThat(blocked.allowed()).isFalse();
          rateLimits.update(blocked.bucket());
        });
    assertThat(
            jdbc.queryForObject(
                "SELECT violation_count FROM rate_limit_buckets WHERE subject_hash = ?",
                Integer.class,
                subjectHash))
        .isEqualTo(1);

    var audits = new JdbcSecurityAuditRepository(jdbc);
    audits.save(
        new SecurityAuditEvent(
            UUID.randomUUID(),
            "AUTH_LOGIN",
            null,
            "/api/auth/login",
            SecurityAuditOutcome.DENIED,
            401,
            UUID.randomUUID(),
            now,
            now.plusSeconds(60)));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM security_audit_events WHERE route_template = ?",
                Integer.class,
                "/api/auth/login"))
        .isEqualTo(1);
    assertThat(audits.deleteExpiredBefore(now.plusSeconds(60))).isEqualTo(1);
  }

  @Test
  void accountDeletionQueueIsCancellableAndUserRemovalPreservesUnlinkedBillingAuditRows() {
    UUID userId = UUID.randomUUID();
    UUID projectId = UUID.randomUUID();
    UUID videoId = UUID.randomUUID();
    UUID pipelineId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    UUID stageRunId = UUID.randomUUID();
    UUID subscriptionId = UUID.randomUUID();
    UUID billingEventId = UUID.randomUUID();
    UUID ledgerId = UUID.randomUUID();
    UUID auditId = UUID.randomUUID();
    Instant now = Instant.parse("2026-10-04T12:00:00Z");
    Timestamp timestamp = Timestamp.from(now);
    jdbc.update(
        "INSERT INTO users (id, email, normalized_email, password_hash, status, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
        userId,
        userId + "@example.test",
        userId + "@example.test",
        "test-hash",
        timestamp,
        timestamp);
    jdbc.update(
        "INSERT INTO projects (id, user_id, name, status, created_at, updated_at) "
            + "VALUES (?, ?, 'Deletion project', 'ACTIVE', ?, ?)",
        projectId,
        userId,
        timestamp,
        timestamp);
    jdbc.update(
        "INSERT INTO videos (id, user_id, project_id, object_key, original_filename, "
            + "declared_content_type, declared_size_bytes, upload_status, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, 'source.mp4', 'video/mp4', 1, 'UPLOADED', ?, ?)",
        videoId,
        userId,
        projectId,
        "users/" + userId + "/projects/" + projectId + "/source/" + videoId + "/original.mp4",
        timestamp,
        timestamp);
    jdbc.update(
        "INSERT INTO pipelines (id, user_id, project_id, video_id, version, status, "
            + "correlation_id, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, 1, 'QUEUED', ?, ?, ?)",
        pipelineId,
        userId,
        projectId,
        videoId,
        UUID.randomUUID(),
        timestamp,
        timestamp);
    jdbc.update(
        "INSERT INTO jobs (id, pipeline_id, user_id, project_id, video_id, operation, version, "
            + "idempotency_key, status, current_stage, attempt, progress, correlation_id, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, 'VIDEO_PROCESS', 1, ?, 'QUEUED', 'INGEST', 1, 0, ?, ?, ?)",
        jobId,
        pipelineId,
        userId,
        projectId,
        videoId,
        "account-delete-" + jobId,
        UUID.randomUUID(),
        timestamp,
        timestamp);
    jdbc.update(
        "INSERT INTO stage_runs (id, job_id, pipeline_version, stage_name, status, attempt, "
            + "progress, created_at, updated_at) "
            + "VALUES (?, ?, 1, 'INGEST', 'QUEUED', 1, 0, ?, ?)",
        stageRunId,
        jobId,
        timestamp,
        timestamp);
    jdbc.update(
        "INSERT INTO outbox_messages (id, aggregate_type, aggregate_id, event_type, routing_key, "
            + "payload, attempt, available_at, created_at) "
            + "VALUES (?, 'JOB', ?, 'job.command.v1', 'jobs.video.process', '{}', 0, ?, ?)",
        UUID.randomUUID(),
        jobId,
        timestamp,
        timestamp);
    jdbc.update(
        "INSERT INTO subscriptions (id, user_id, plan_code, status, period_start, period_end, "
            + "created_at, updated_at) VALUES (?, ?, 'PRO', 'ACTIVE', ?, ?, ?, ?)",
        subscriptionId,
        userId,
        timestamp,
        Timestamp.from(now.plusSeconds(86_400)),
        timestamp,
        timestamp);
    jdbc.update(
        "INSERT INTO billing_events (id, provider_code, provider_event_id, event_version, "
            + "event_type, processing_status, occurred_at, user_id, subscription_id, "
            + "amount_minor_units, currency, received_at, processed_at) "
            + "VALUES (?, 'sandbox', ?, 1, 'SUBSCRIPTION_CREATED', 'APPLIED', ?, ?, ?, "
            + "1200, 'USD', ?, ?)",
        billingEventId,
        "provider-event-" + billingEventId,
        timestamp,
        userId,
        subscriptionId,
        timestamp,
        timestamp);
    jdbc.update(
        "INSERT INTO billing_ledger_entries (id, user_id, subscription_id, billing_event_id, "
            + "entry_type, amount_minor_units, currency, created_at) "
            + "VALUES (?, ?, ?, ?, 'CHARGE', 1200, 'USD', ?)",
        ledgerId,
        userId,
        subscriptionId,
        billingEventId,
        timestamp);
    jdbc.update(
        "INSERT INTO security_audit_events (id, event_type, actor_user_id, route_template, "
            + "outcome, http_status, correlation_id, occurred_at, expires_at) "
            + "VALUES (?, 'AUTH_LOGIN', ?, '/api/auth/login', 'SUCCESS', 200, ?, ?, ?)",
        auditId,
        userId,
        UUID.randomUUID(),
        timestamp,
        Timestamp.from(now.plusSeconds(60)));

    JdbcAccountDeletionRepository deletionRepository = new JdbcAccountDeletionRepository(jdbc);
    Instant deleteAfter = now.plusSeconds(30L * 24 * 60 * 60);
    deletionRepository.enqueue(userId, now, deleteAfter);
    deletionRepository.enqueue(userId, now.plusSeconds(1), deleteAfter.plusSeconds(1));
    assertThat(deletionRepository.findPendingDeleteAfter(userId)).contains(deleteAfter);
    assertThat(jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, jobId))
        .isEqualTo("CANCELLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM pipelines WHERE id = ?", String.class, pipelineId))
        .isEqualTo("CANCELLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM stage_runs WHERE id = ?", String.class, stageRunId))
        .isEqualTo("FAILED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_messages WHERE aggregate_id = ?",
                Integer.class,
                jobId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM subscriptions WHERE id = ?", String.class, subscriptionId))
        .isEqualTo("CANCELED");
    assertThat(deletionRepository.cancelPending(userId, now.plusSeconds(1))).isTrue();
    assertThat(deletionRepository.cancelPending(userId, now.plusSeconds(1))).isFalse();

    jdbc.update("UPDATE subscriptions SET user_id = NULL WHERE user_id = ?", userId);
    jdbc.update("UPDATE billing_events SET user_id = NULL WHERE user_id = ?", userId);
    jdbc.update("UPDATE billing_ledger_entries SET user_id = NULL WHERE user_id = ?", userId);
    jdbc.update("DELETE FROM users WHERE id = ?", userId);

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM projects WHERE id = ?", Integer.class, projectId))
        .isZero();
    assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM videos WHERE id = ?", Integer.class, videoId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM pipelines WHERE id = ?", Integer.class, pipelineId))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE id = ?", Integer.class, jobId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM stage_runs WHERE id = ?", Integer.class, stageRunId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT user_id FROM subscriptions WHERE id = ?", UUID.class, subscriptionId))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT user_id FROM billing_events WHERE id = ?", UUID.class, billingEventId))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT user_id FROM billing_ledger_entries WHERE id = ?", UUID.class, ledgerId))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT actor_user_id FROM security_audit_events WHERE id = ?",
                UUID.class,
                auditId))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_deletion_requests "
                    + "WHERE user_id IS NULL AND status = 'CANCELLED'",
                Integer.class))
        .isEqualTo(1);
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
