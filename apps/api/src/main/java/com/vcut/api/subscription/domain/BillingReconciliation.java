package com.vcut.api.subscription.domain;

import java.util.Currency;
import java.util.Objects;

public record BillingReconciliation(
    Currency currency,
    long appliedEventCount,
    long ledgerEntryCount,
    long providerNetMinorUnits,
    long ledgerNetMinorUnits) {

  public BillingReconciliation {
    Objects.requireNonNull(currency, "currency");
    if (appliedEventCount < 0 || ledgerEntryCount < 0) {
      throw new IllegalArgumentException("billing reconciliation counts must not be negative");
    }
  }

  public long discrepancyMinorUnits() {
    return Math.subtractExact(providerNetMinorUnits, ledgerNetMinorUnits);
  }
}
