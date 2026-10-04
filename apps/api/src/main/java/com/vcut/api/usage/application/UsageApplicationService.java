package com.vcut.api.usage.application;

import com.vcut.api.shared.errors.ConflictException;
import com.vcut.api.shared.errors.QuotaExceededException;
import com.vcut.api.shared.errors.ValidationException;
import com.vcut.api.usage.domain.PlanCode;
import com.vcut.api.usage.domain.QuotaReservation;
import com.vcut.api.usage.domain.QuotaReservationStatus;
import com.vcut.api.usage.domain.Subscription;
import com.vcut.api.usage.domain.UsageCostRates;
import com.vcut.api.usage.domain.UsageLedgerEntry;
import com.vcut.api.usage.domain.UsageMetrics;
import com.vcut.api.usage.domain.UsagePeriod;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UsageApplicationService {

  private static final int LEDGER_HISTORY_LIMIT = 100;
  private static final BigDecimal SECONDS_PER_MINUTE = BigDecimal.valueOf(60);

  private final UsageRepository usageRepository;
  private final SubscriptionRepository subscriptionRepository;
  private final PlanLimitsProvider planLimitsProvider;
  private final UsageCostCalculator costCalculator;
  private final Clock clock;

  @Autowired
  public UsageApplicationService(
      UsageRepository usageRepository,
      SubscriptionRepository subscriptionRepository,
      PlanLimitsProvider planLimitsProvider,
      UsageCostRates costRates) {
    this(
        usageRepository,
        subscriptionRepository,
        planLimitsProvider,
        new UsageCostCalculator(costRates),
        Clock.systemUTC());
  }

  UsageApplicationService(
      UsageRepository usageRepository,
      SubscriptionRepository subscriptionRepository,
      PlanLimitsProvider planLimitsProvider,
      UsageCostCalculator costCalculator,
      Clock clock) {
    this.usageRepository = usageRepository;
    this.subscriptionRepository = subscriptionRepository;
    this.planLimitsProvider = planLimitsProvider;
    this.costCalculator = costCalculator;
    this.clock = clock;
  }

  @Transactional
  public QuotaReservation reserveProcessingMinutes(
      UUID userId, UUID resourceId, String operation, int version, BigDecimal durationSeconds) {
    if (version < 1 || operation == null || operation.isBlank()) {
      throw new IllegalArgumentException("usage operation and version are required");
    }
    BigDecimal requestedMinutes = processingMinutesForSeconds(durationSeconds);
    Instant now = clock.instant();
    PeriodBounds bounds = currentPeriod(now);
    PlanCode planCode = currentPlan(userId, now);
    PlanLimits limits = planLimitsProvider.limitsFor(planCode);
    UsagePeriod period = lockPeriod(userId, planCode, bounds);
    String idempotencyKey = reservationKey(resourceId, operation, version);
    var existing = usageRepository.findReservationForUpdate(idempotencyKey);
    if (existing.isPresent()) {
      QuotaReservation reservation = existing.get();
      if (!reservation.userId().equals(userId) || !reservation.resourceId().equals(resourceId)) {
        throw new ConflictException(
            "Quota reservation key is already assigned to another resource.");
      }
      if (reservation.status() == QuotaReservationStatus.RELEASED) {
        throw new ConflictException("Released quota reservations require a new operation version.");
      }
      return reservation;
    }

    int activeJobs = usageRepository.countActiveJobs(userId);
    if (activeJobs >= limits.maxConcurrentJobs()) {
      throw new ValidationException(
          "Concurrent job limit reached for the current plan ("
              + limits.maxConcurrentJobs()
              + ").");
    }
    BigDecimal available =
        limits
            .monthlyProcessingMinutes()
            .subtract(period.processedMinutes())
            .subtract(period.reservedMinutes())
            .max(BigDecimal.ZERO);
    if (requestedMinutes.compareTo(available) > 0) {
      throw new QuotaExceededException(
          requestedMinutes, available, limits.monthlyProcessingMinutes());
    }

    QuotaReservation reservation =
        new QuotaReservation(
            UUID.randomUUID(),
            period.id(),
            userId,
            resourceId,
            operation,
            idempotencyKey,
            requestedMinutes,
            QuotaReservationStatus.RESERVED,
            now,
            now);
    usageRepository.saveReservation(reservation);
    usageRepository.updatePeriod(
        period.withProcessingTotals(
            period.reservedMinutes().add(requestedMinutes), period.processedMinutes(), now));
    return reservation;
  }

  @Transactional
  public void confirmProcessing(
      UUID userId,
      UUID resourceId,
      String operation,
      int version,
      BigDecimal actualDurationSeconds) {
    String idempotencyKey = reservationKey(resourceId, operation, version);
    QuotaReservation reservation =
        usageRepository
            .findReservationForUpdate(idempotencyKey)
            .orElseThrow(() -> new ConflictException("Quota reservation was not found."));
    if (!reservation.userId().equals(userId)) {
      throw new ConflictException("Quota reservation does not belong to the user.");
    }
    if (reservation.status() == QuotaReservationStatus.CONFIRMED) {
      return;
    }
    if (reservation.status() != QuotaReservationStatus.RESERVED) {
      throw new ConflictException("Quota reservation is no longer active.");
    }
    UsagePeriod period =
        usageRepository
            .findPeriodByIdForUpdate(reservation.periodId())
            .orElseThrow(() -> new ConflictException("Usage period is no longer active."));
    Instant now = clock.instant();
    BigDecimal actualMinutes = processingMinutesForSeconds(actualDurationSeconds);
    BigDecimal remainingReserved = period.reservedMinutes().subtract(reservation.reservedMinutes());
    if (remainingReserved.signum() < 0) {
      throw new IllegalStateException("usage period reservation totals are inconsistent");
    }
    BigDecimal transcriptionMinutes =
        operation.startsWith("TRANSCRIPTION") ? actualMinutes : BigDecimal.ZERO;
    UsageMetrics metrics =
        new UsageMetrics(
            transcriptionMinutes, BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0);
    BigDecimal cost = costCalculator.calculate(metrics);
    UsagePeriod updatedPeriod =
        period
            .withProcessingTotals(
                remainingReserved, period.processedMinutes().add(actualMinutes), now)
            .withAdditionalMetrics(
                0,
                0,
                transcriptionMinutes,
                BigDecimal.ZERO,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
                cost,
                now);
    QuotaReservation confirmed = reservation.confirm(now);
    usageRepository.updatePeriod(updatedPeriod);
    usageRepository.updateReservation(confirmed);
    usageRepository.saveLedgerEntry(
        ledgerEntry(
            reservation,
            reservation.reservedMinutes(),
            actualMinutes,
            metrics,
            cost,
            "SUCCEEDED",
            now));
  }

  @Transactional
  public void releaseProcessingReservation(
      UUID userId, UUID resourceId, String operation, int version) {
    String idempotencyKey = reservationKey(resourceId, operation, version);
    var found = usageRepository.findReservationForUpdate(idempotencyKey);
    if (found.isEmpty() || found.get().status() != QuotaReservationStatus.RESERVED) {
      return;
    }
    QuotaReservation reservation = found.get();
    if (!reservation.userId().equals(userId)) {
      throw new ConflictException("Quota reservation does not belong to the user.");
    }
    UsagePeriod period =
        usageRepository
            .findPeriodByIdForUpdate(reservation.periodId())
            .orElseThrow(() -> new ConflictException("Usage period was not found."));
    Instant now = clock.instant();
    BigDecimal remainingReserved = period.reservedMinutes().subtract(reservation.reservedMinutes());
    if (remainingReserved.signum() < 0) {
      throw new IllegalStateException("usage period reservation totals are inconsistent");
    }
    QuotaReservation released = reservation.release(now);
    UsageMetrics metrics = UsageMetrics.empty();
    usageRepository.updatePeriod(
        period.withProcessingTotals(remainingReserved, period.processedMinutes(), now));
    usageRepository.updateReservation(released);
    usageRepository.saveLedgerEntry(
        ledgerEntry(
            reservation,
            reservation.reservedMinutes(),
            BigDecimal.ZERO,
            metrics,
            BigDecimal.ZERO,
            "RELEASED",
            now));
  }

  @Transactional
  public void recordMetrics(
      UUID userId,
      UUID resourceId,
      String operation,
      int version,
      UsageMetrics metrics,
      String outcome) {
    if (version < 1 || operation == null || operation.isBlank()) {
      throw new IllegalArgumentException("usage operation and version are required");
    }
    if (!"SUCCEEDED".equals(outcome) && !"FAILED".equals(outcome) && !"RELEASED".equals(outcome)) {
      throw new IllegalArgumentException("unsupported usage outcome");
    }
    String idempotencyKey = reservationKey(resourceId, operation, version) + ":METRICS";
    Instant now = clock.instant();
    PeriodBounds bounds = currentPeriod(now);
    PlanCode planCode = currentPlan(userId, now);
    UsagePeriod period = lockPeriod(userId, planCode, bounds);
    if (usageRepository.findLedgerByIdempotencyKey(idempotencyKey).isPresent()) {
      return;
    }
    BigDecimal cost = costCalculator.calculate(metrics);
    UsagePeriod updated =
        period.withAdditionalMetrics(
            metrics.storageBytes(),
            metrics.renders(),
            metrics.transcriptionMinutes(),
            metrics.multimodalMinutes(),
            metrics.llmTokens(),
            metrics.cpuSeconds(),
            metrics.gpuSeconds(),
            metrics.bandwidthBytes(),
            cost,
            now);
    usageRepository.updatePeriod(updated);
    usageRepository.saveLedgerEntry(
        new UsageLedgerEntry(
            UUID.randomUUID(),
            period.id(),
            userId,
            resourceId,
            operation,
            idempotencyKey,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            metrics.storageBytes(),
            metrics.renders(),
            metrics.transcriptionMinutes(),
            metrics.multimodalMinutes(),
            metrics.llmTokens(),
            metrics.cpuSeconds(),
            metrics.gpuSeconds(),
            metrics.bandwidthBytes(),
            cost,
            outcome,
            now));
  }

  @Transactional
  public void assertUploadAllowed(UUID userId, long sizeBytes) {
    if (sizeBytes <= 0) {
      throw new ValidationException("File size must be positive.");
    }
    Instant now = clock.instant();
    PlanCode planCode = currentPlan(userId, now);
    PlanLimits limits = planLimitsProvider.limitsFor(planCode);
    lockPeriod(userId, planCode, currentPeriod(now));
    if (sizeBytes > limits.maxFileSizeBytes()) {
      throw new ValidationException("File size exceeds the current plan limit.");
    }
    long retainedBytes = usageRepository.retainedBytes(userId);
    if (retainedBytes > limits.maxStorageBytes() - sizeBytes) {
      throw new ValidationException("Stored media would exceed the current plan storage limit.");
    }
  }

  @Transactional(readOnly = true)
  public PlanLimits limitsForUser(UUID userId) {
    return planLimitsProvider.limitsFor(currentPlan(userId, clock.instant()));
  }

  @Transactional
  public void synchronizePlanSnapshot(UUID userId, PlanCode planCode) {
    Instant now = clock.instant();
    PeriodBounds bounds = currentPeriod(now);
    UsagePeriod period =
        usageRepository.lockOrCreatePeriod(
            UsagePeriod.empty(
                UUID.randomUUID(), userId, planCode, bounds.start(), bounds.end(), now));
    if (period.planCode() != planCode) {
      usageRepository.updatePeriod(period.withPlanCode(planCode, now));
    }
  }

  @Transactional(readOnly = true)
  public int workerPriorityForUser(UUID userId) {
    return planLimitsProvider.limitsFor(currentPlan(userId, clock.instant())).workerPriority();
  }

  @Transactional(readOnly = true)
  public void validateVideoMetadata(
      UUID userId, BigDecimal durationSeconds, Integer width, Integer height) {
    PlanLimits limits = planLimitsProvider.limitsFor(currentPlan(userId, clock.instant()));
    if (durationSeconds == null
        || durationSeconds.signum() <= 0
        || durationSeconds.compareTo(BigDecimal.valueOf(limits.maxDurationSeconds())) > 0) {
      throw new ValidationException("Video duration exceeds the current plan limit.");
    }
    if (width == null
        || height == null
        || width > limits.maxWidth()
        || height > limits.maxHeight()) {
      throw new ValidationException("Video resolution exceeds the current plan limit.");
    }
  }

  @Transactional
  public void assertRenderAllowed(
      UUID userId, BigDecimal durationSeconds, int width, int height, long estimatedOutputBytes) {
    PlanLimits limits = planLimitsProvider.limitsFor(currentPlan(userId, clock.instant()));
    if (durationSeconds == null
        || durationSeconds.signum() <= 0
        || durationSeconds.compareTo(BigDecimal.valueOf(limits.maxDurationSeconds())) > 0) {
      throw new ValidationException("Render duration exceeds the current plan limit.");
    }
    if (width > limits.maxWidth() || height > limits.maxHeight()) {
      throw new ValidationException("Render quality exceeds the current plan limit.");
    }
    assertUploadAllowed(userId, estimatedOutputBytes);
  }

  @Transactional
  public UsageSummary currentSummary(UUID userId) {
    Instant now = clock.instant();
    PlanCode planCode = currentPlan(userId, now);
    PlanLimits limits = planLimitsProvider.limitsFor(planCode);
    PeriodBounds bounds = currentPeriod(now);
    UsagePeriod period = lockPeriod(userId, planCode, bounds);
    BigDecimal remaining =
        limits
            .monthlyProcessingMinutes()
            .subtract(period.processedMinutes())
            .subtract(period.reservedMinutes())
            .max(BigDecimal.ZERO);
    return new UsageSummary(
        planCode,
        period.periodStart(),
        period.periodEnd(),
        limits.monthlyProcessingMinutes(),
        period.reservedMinutes(),
        period.processedMinutes(),
        remaining,
        period.storedBytes(),
        usageRepository.retainedBytes(userId),
        limits.maxStorageBytes(),
        period.renders(),
        period.estimatedCost(),
        usageRepository.countActiveJobs(userId),
        limits.maxConcurrentJobs(),
        usageRepository.recentLedger(userId, period.periodStart(), LEDGER_HISTORY_LIMIT));
  }

  public static BigDecimal processingMinutesForSeconds(BigDecimal seconds) {
    if (seconds == null || seconds.signum() <= 0) {
      throw new ValidationException("Processing duration must be positive.");
    }
    return seconds.divide(SECONDS_PER_MINUTE, 0, RoundingMode.CEILING);
  }

  public static String reservationKey(UUID resourceId, String operation, int version) {
    return resourceId + ":" + operation + ":" + version;
  }

  private PlanCode currentPlan(UUID userId, Instant now) {
    return subscriptionRepository
        .findActiveForUser(userId, now)
        .filter(subscription -> subscription.isActiveAt(now))
        .map(Subscription::planCode)
        .orElse(PlanCode.FREE);
  }

  private UsagePeriod lockPeriod(UUID userId, PlanCode planCode, PeriodBounds bounds) {
    Instant now = clock.instant();
    UsagePeriod period =
        usageRepository.lockOrCreatePeriod(
            UsagePeriod.empty(
                UUID.randomUUID(), userId, planCode, bounds.start(), bounds.end(), now));
    if (period.planCode() != planCode) {
      period = period.withPlanCode(planCode, now);
      usageRepository.updatePeriod(period);
    }
    return period;
  }

  private static PeriodBounds currentPeriod(Instant instant) {
    YearMonth month = YearMonth.from(instant.atZone(ZoneOffset.UTC));
    return new PeriodBounds(
        month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
        month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
  }

  private static UsageLedgerEntry ledgerEntry(
      QuotaReservation reservation,
      BigDecimal reservedMinutes,
      BigDecimal processedMinutes,
      UsageMetrics metrics,
      BigDecimal cost,
      String outcome,
      Instant now) {
    return new UsageLedgerEntry(
        UUID.randomUUID(),
        reservation.periodId(),
        reservation.userId(),
        reservation.resourceId(),
        reservation.operation(),
        reservation.idempotencyKey() + ":" + outcome,
        reservedMinutes,
        processedMinutes,
        metrics.storageBytes(),
        metrics.renders(),
        metrics.transcriptionMinutes(),
        metrics.multimodalMinutes(),
        metrics.llmTokens(),
        metrics.cpuSeconds(),
        metrics.gpuSeconds(),
        metrics.bandwidthBytes(),
        cost,
        outcome,
        now);
  }

  private record PeriodBounds(Instant start, Instant end) {}
}
