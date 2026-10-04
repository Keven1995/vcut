package com.vcut.api.subscription.application;

import com.vcut.api.subscription.domain.BillingEventRecord;
import com.vcut.api.subscription.domain.BillingLedgerEntry;
import com.vcut.api.subscription.domain.BillingReconciliation;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillingRepository {

  boolean registerReceived(BillingEventRecord eventRecord);

  Optional<BillingEventRecord> findByProviderAndEventId(String provider, String providerEventId);

  void completeEvent(BillingEventRecord eventRecord);

  void saveLedgerEntry(BillingLedgerEntry entry);

  List<BillingLedgerEntry> recentEntriesForUser(UUID userId, int limit);

  BillingReconciliation reconcileForUser(UUID userId, Currency currency);
}
