package com.vcut.api.subscription.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vcut.api.usage.domain.PlanCode;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SubscriptionDomainTest {

  private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

  @ParameterizedTest
  @CsvSource({"PAYMENT_FAILED,PAST_DUE", "SUBSCRIPTION_CANCELED,CANCELED", "CHARGEBACK,CHARGEBACK"})
  void billingEventsDriveOnlyTheirDefinedSubscriptionTransition(
      BillingEventType eventType, SubscriptionStatus expectedStatus) {
    PaidSubscription current = activeSubscription();

    PaidSubscription updated =
        switch (eventType) {
          case PAYMENT_FAILED -> current.paymentFailed(NOW.plusSeconds(1));
          case SUBSCRIPTION_CANCELED -> current.canceled(NOW.plusSeconds(1));
          case CHARGEBACK -> current.chargedBack(NOW.plusSeconds(1));
          default -> throw new IllegalArgumentException("unexpected event type");
        };

    assertThat(updated.status()).isEqualTo(expectedStatus);
    assertThat(updated.lastEventAt()).isEqualTo(NOW.plusSeconds(1));
  }

  @Test
  void cancellationScheduledAtPeriodEndKeepsPaidBenefitsUntilThePeriodEnds() {
    PaidSubscription scheduled = activeSubscription().scheduleCancellation(NOW.plusSeconds(1));

    assertThat(scheduled.status()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(scheduled.cancelAtPeriodEnd()).isTrue();
    assertThat(scheduled.isActiveAt(NOW.plusSeconds(2))).isTrue();
    assertThat(scheduled.isActiveAt(scheduled.period().endsAt())).isFalse();
  }

  @Test
  void renewalStartsAtOrAfterTheCurrentPeriodAndClearsScheduledCancellation() {
    PaidSubscription scheduled = activeSubscription().scheduleCancellation(NOW.plusSeconds(1));
    SubscriptionPeriod nextPeriod =
        new SubscriptionPeriod(
            scheduled.period().endsAt(), scheduled.period().endsAt().plusSeconds(60));

    PaidSubscription renewed =
        scheduled.renewed(
            nextPeriod,
            scheduled.monthlyPriceMinorUnits(),
            scheduled.currency(),
            nextPeriod.startsAt());

    assertThat(renewed.status()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(renewed.cancelAtPeriodEnd()).isFalse();
    assertThat(renewed.period()).isEqualTo(nextPeriod);
  }

  @Test
  void terminalSubscriptionCannotBeRenewed() {
    PaidSubscription chargedBack = activeSubscription().chargedBack(NOW.plusSeconds(1));

    assertThatThrownBy(
            () ->
                chargedBack.renewed(
                    new SubscriptionPeriod(NOW.plusSeconds(60), NOW.plusSeconds(120)),
                    chargedBack.monthlyPriceMinorUnits(),
                    chargedBack.currency(),
                    NOW.plusSeconds(61)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void checkoutCompletionAndBillingLedgerPreserveOnlyNonSensitivePaymentFacts() {
    UUID sessionId = UUID.randomUUID();
    CheckoutSession pending =
        new CheckoutSession(
            sessionId,
            UUID.randomUUID(),
            PlanCode.PRO,
            CheckoutStatus.PENDING,
            "sandbox",
            "checkout-session-1",
            "customer-1",
            "https://checkout.example.test/session/1",
            1_200,
            Currency.getInstance("USD"),
            NOW.plusSeconds(900),
            NOW,
            NOW,
            null);
    CheckoutSession completed = pending.complete(NOW.plusSeconds(2));
    BillingLedgerEntry charge =
        new BillingLedgerEntry(
            UUID.randomUUID(),
            pending.userId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            BillingLedgerType.CHARGE,
            pending.amountMinorUnits(),
            pending.currency(),
            NOW.plusSeconds(2));

    assertThat(completed.status()).isEqualTo(CheckoutStatus.COMPLETED);
    assertThat(completed.completedAt()).isEqualTo(NOW.plusSeconds(2));
    assertThat(charge.amountMinorUnits()).isEqualTo(1_200);
    assertThat(charge.currency()).isEqualTo(Currency.getInstance("USD"));
  }

  private static PaidSubscription activeSubscription() {
    return new PaidSubscription(
        UUID.randomUUID(),
        UUID.randomUUID(),
        PlanCode.PRO,
        SubscriptionStatus.ACTIVE,
        "sandbox",
        "customer-1",
        "subscription-1",
        1_200,
        Currency.getInstance("USD"),
        new SubscriptionPeriod(NOW, NOW.plusSeconds(60)),
        false,
        null,
        NOW,
        NOW);
  }
}
