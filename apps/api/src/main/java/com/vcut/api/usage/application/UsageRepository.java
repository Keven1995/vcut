package com.vcut.api.usage.application;

import com.vcut.api.usage.domain.QuotaReservation;
import com.vcut.api.usage.domain.UsageLedgerEntry;
import com.vcut.api.usage.domain.UsagePeriod;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UsageRepository {

  Optional<UsagePeriod> findPeriodForUpdate(UUID userId, Instant periodStart);

  Optional<UsagePeriod> findPeriodByIdForUpdate(UUID periodId);

  UsagePeriod lockOrCreatePeriod(UsagePeriod initialPeriod);

  UsagePeriod savePeriod(UsagePeriod period);

  Optional<QuotaReservation> findReservationForUpdate(String idempotencyKey);

  QuotaReservation saveReservation(QuotaReservation reservation);

  void updatePeriod(UsagePeriod period);

  void updateReservation(QuotaReservation reservation);

  void saveLedgerEntry(UsageLedgerEntry entry);

  Optional<UsageLedgerEntry> findLedgerByIdempotencyKey(String idempotencyKey);

  List<UsageLedgerEntry> recentLedger(UUID userId, Instant periodStart, int limit);

  long retainedBytes(UUID userId);

  int countActiveJobs(UUID userId);
}
