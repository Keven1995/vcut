package com.vcut.api.subscription.presentation;

import com.vcut.api.subscription.application.SubscriptionOverview;
import com.vcut.api.subscription.domain.BillingLedgerEntry;
import com.vcut.api.subscription.domain.BillingReconciliation;
import com.vcut.api.subscription.domain.CheckoutSession;
import com.vcut.api.subscription.domain.PaidSubscription;
import com.vcut.api.subscription.domain.SubscriptionPlan;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SubscriptionOverviewResponse(
    boolean sandboxEnabled,
    List<PlanResponse> plans,
    SubscriptionResponse currentSubscription,
    List<CheckoutResponse> checkoutSessions,
    List<PaymentResponse> recentPayments,
    ReconciliationResponse reconciliation) {

  public SubscriptionOverviewResponse {
    plans = List.copyOf(plans);
    checkoutSessions = List.copyOf(checkoutSessions);
    recentPayments = List.copyOf(recentPayments);
  }

  public static SubscriptionOverviewResponse from(SubscriptionOverview overview) {
    return new SubscriptionOverviewResponse(
        overview.sandboxEnabled(),
        overview.plans().stream().map(PlanResponse::from).toList(),
        overview.currentSubscription() == null
            ? null
            : SubscriptionResponse.from(overview.currentSubscription()),
        overview.checkoutSessions().stream().map(CheckoutResponse::from).toList(),
        overview.recentPayments().stream().map(PaymentResponse::from).toList(),
        ReconciliationResponse.from(overview.reconciliation()));
  }

  public record PlanResponse(
      String planCode,
      String displayName,
      long monthlyPriceMinorUnits,
      String currency,
      java.math.BigDecimal monthlyProcessingMinutes,
      long maxFileSizeBytes,
      long maxDurationSeconds,
      long maxStorageBytes,
      int maxConcurrentJobs,
      int workerPriority,
      int maxWidth,
      int maxHeight,
      RetentionResponse retention) {

    private static PlanResponse from(SubscriptionPlan plan) {
      var benefits = plan.benefits();
      var retention = benefits.retentionPolicy();
      return new PlanResponse(
          plan.code().name(),
          plan.displayName(),
          plan.monthlyPriceMinorUnits(),
          plan.currency().getCurrencyCode(),
          benefits.monthlyProcessingMinutes(),
          benefits.maxFileSizeBytes(),
          benefits.maxDurationSeconds(),
          benefits.maxStorageBytes(),
          benefits.maxConcurrentJobs(),
          benefits.workerPriority(),
          benefits.maxWidth(),
          benefits.maxHeight(),
          new RetentionResponse(
              retention.originalMediaDays(),
              retention.audioDays(),
              retention.framesDays(),
              retention.previewDays(),
              retention.finalDays(),
              retention.thumbnailDays(),
              retention.failedJobArtifactDays()));
    }
  }

  public record RetentionResponse(
      long originalDays,
      long audioDays,
      long framesDays,
      long previewDays,
      long finalDays,
      long thumbnailDays,
      long failedJobArtifactDays) {}

  public record SubscriptionResponse(
      UUID id,
      String planCode,
      String status,
      Instant periodStart,
      Instant periodEnd,
      boolean cancelAtPeriodEnd,
      long monthlyPriceMinorUnits,
      String currency,
      Instant createdAt) {

    private static SubscriptionResponse from(PaidSubscription subscription) {
      return new SubscriptionResponse(
          subscription.id(),
          subscription.planCode().name(),
          subscription.status().name(),
          subscription.period().startsAt(),
          subscription.period().endsAt(),
          subscription.cancelAtPeriodEnd(),
          subscription.monthlyPriceMinorUnits(),
          subscription.currency().getCurrencyCode(),
          subscription.createdAt());
    }
  }

  public record CheckoutResponse(
      UUID id,
      String planCode,
      String status,
      String checkoutUrl,
      long amountMinorUnits,
      String currency,
      Instant expiresAt,
      Instant createdAt,
      Instant completedAt) {

    private static CheckoutResponse from(CheckoutSession session) {
      return new CheckoutResponse(
          session.id(),
          session.planCode().name(),
          session.status().name(),
          session.checkoutUrl(),
          session.amountMinorUnits(),
          session.currency().getCurrencyCode(),
          session.expiresAt(),
          session.createdAt(),
          session.completedAt());
    }
  }

  public record PaymentResponse(
      String type, long amountMinorUnits, String currency, Instant occurredAt) {

    private static PaymentResponse from(BillingLedgerEntry entry) {
      return new PaymentResponse(
          entry.type().name(),
          entry.amountMinorUnits(),
          entry.currency().getCurrencyCode(),
          entry.createdAt());
    }
  }

  public record ReconciliationResponse(
      String currency,
      long appliedEventCount,
      long ledgerEntryCount,
      long providerNetMinorUnits,
      long ledgerNetMinorUnits,
      long discrepancyMinorUnits) {

    private static ReconciliationResponse from(BillingReconciliation reconciliation) {
      return new ReconciliationResponse(
          reconciliation.currency().getCurrencyCode(),
          reconciliation.appliedEventCount(),
          reconciliation.ledgerEntryCount(),
          reconciliation.providerNetMinorUnits(),
          reconciliation.ledgerNetMinorUnits(),
          reconciliation.discrepancyMinorUnits());
    }
  }
}
