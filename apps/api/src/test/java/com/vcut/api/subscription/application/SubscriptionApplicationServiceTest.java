package com.vcut.api.subscription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vcut.api.subscription.domain.BillingEventRecord;
import com.vcut.api.subscription.domain.BillingEventStatus;
import com.vcut.api.subscription.domain.BillingEventType;
import com.vcut.api.subscription.domain.BillingLedgerEntry;
import com.vcut.api.subscription.domain.CheckoutSession;
import com.vcut.api.subscription.domain.CheckoutStatus;
import com.vcut.api.subscription.domain.PaidSubscription;
import com.vcut.api.subscription.domain.PaymentCustomer;
import com.vcut.api.subscription.domain.PaymentEvent;
import com.vcut.api.subscription.domain.SubscriptionPeriod;
import com.vcut.api.subscription.domain.SubscriptionPlan;
import com.vcut.api.subscription.domain.SubscriptionStatus;
import com.vcut.api.usage.domain.PlanCode;
import com.vcut.api.usage.domain.RetentionPolicy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class SubscriptionApplicationServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
  private static final Currency USD = Currency.getInstance("USD");

  private PaidSubscriptionRepository subscriptions;
  private CheckoutRepository checkouts;
  private PaymentCustomerRepository customers;
  private BillingRepository billing;
  private PaymentProvider provider;
  private SandboxPaymentSimulator simulator;
  private SubscriptionPlanCatalog catalog;
  private SubscriptionPricingProvider pricing;
  private PlanEntitlementSync planEntitlementSync;
  private SubscriptionApplicationService service;

  @BeforeEach
  void setUp() {
    subscriptions = mock(PaidSubscriptionRepository.class);
    checkouts = mock(CheckoutRepository.class);
    customers = mock(PaymentCustomerRepository.class);
    billing = mock(BillingRepository.class);
    provider = mock(PaymentProvider.class);
    simulator = mock(SandboxPaymentSimulator.class);
    catalog = mock(SubscriptionPlanCatalog.class);
    pricing = mock(SubscriptionPricingProvider.class);
    planEntitlementSync = mock(PlanEntitlementSync.class);
    when(provider.providerCode()).thenReturn("sandbox");
    when(pricing.checkoutTtl()).thenReturn(java.time.Duration.ofMinutes(30));
    when(pricing.currency()).thenReturn(USD);
    service =
        new SubscriptionApplicationService(
            subscriptions,
            checkouts,
            customers,
            billing,
            provider,
            simulator,
            catalog,
            pricing,
            planEntitlementSync,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void checkoutIsAssociatedToTheAuthenticatedUserAndRemainsPendingUntilAWebhook() {
    UUID userId = UUID.randomUUID();
    when(subscriptions.findLatestForUser(userId)).thenReturn(Optional.empty());
    when(checkouts.findPendingForUser(userId, NOW)).thenReturn(Optional.empty());
    when(customers.findByUserAndProvider(userId, "sandbox"))
        .thenReturn(Optional.of(paymentCustomer(userId, "sandbox-customer-existing")));
    when(catalog.planFor(PlanCode.PRO)).thenReturn(proPlan());
    when(provider.createCheckout(
            any(), eq("sandbox-customer-existing"), eq(PlanCode.PRO), eq(1_200L), eq(USD), any()))
        .thenAnswer(
            invocation ->
                new PaymentProvider.ProviderCheckout(
                    "provider-session-1",
                    "sandbox-customer-existing",
                    "https://checkout.example.test/provider-session-1",
                    invocation.getArgument(5)));
    when(checkouts.save(any(CheckoutSession.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(customers.saveOrUpdate(any(PaymentCustomer.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    CheckoutSession checkout = service.createCheckout(userId, PlanCode.PRO);

    assertThat(checkout.userId()).isEqualTo(userId);
    assertThat(checkout.status()).isEqualTo(CheckoutStatus.PENDING);
    assertThat(checkout.providerSessionId()).isEqualTo("provider-session-1");
    verify(subscriptions, never()).save(any(PaidSubscription.class));
    verify(checkouts).update(checkout);
    verify(customers).saveOrUpdate(any(PaymentCustomer.class));
  }

  @Test
  void onlyAValidCheckoutCompletionWebhookActivatesThePaidPlanAndWritesOneLedgerEntry() {
    UUID userId = UUID.randomUUID();
    CheckoutSession checkout = pendingCheckout(userId);
    PaymentEvent event = checkoutCreatedEvent(checkout);
    when(provider.verifyWebhook("timestamp", "signature", "{}")).thenReturn(event);
    when(billing.registerReceived(any(BillingEventRecord.class))).thenReturn(true);
    when(checkouts.findByProviderSessionIdForUpdate("provider-session-1"))
        .thenReturn(Optional.of(checkout));
    when(subscriptions.findLatestForUser(userId)).thenReturn(Optional.empty());
    when(subscriptions.save(any(PaidSubscription.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    WebhookProcessingResult result = service.handleWebhook("timestamp", "signature", "{}");

    assertThat(result.duplicate()).isFalse();
    assertThat(result.status()).isEqualTo(BillingEventStatus.APPLIED);
    ArgumentCaptor<PaidSubscription> subscriptionCaptor =
        ArgumentCaptor.forClass(PaidSubscription.class);
    verify(subscriptions).save(subscriptionCaptor.capture());
    assertThat(subscriptionCaptor.getValue().status()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(subscriptionCaptor.getValue().planCode()).isEqualTo(PlanCode.PRO);
    verify(planEntitlementSync).synchronize(userId, PlanCode.PRO);
    ArgumentCaptor<BillingLedgerEntry> ledgerCaptor =
        ArgumentCaptor.forClass(BillingLedgerEntry.class);
    verify(billing).saveLedgerEntry(ledgerCaptor.capture());
    assertThat(ledgerCaptor.getValue().amountMinorUnits()).isEqualTo(1_200);
    assertThat(ledgerCaptor.getValue().type().name()).isEqualTo("CHARGE");
    ArgumentCaptor<CheckoutSession> checkoutCaptor = ArgumentCaptor.forClass(CheckoutSession.class);
    verify(checkouts).update(checkoutCaptor.capture());
    assertThat(checkoutCaptor.getValue().status()).isEqualTo(CheckoutStatus.COMPLETED);
  }

  @Test
  void duplicateProviderEventIsAcknowledgedWithoutSecondLedgerEntry() {
    UUID userId = UUID.randomUUID();
    PaymentEvent event = checkoutCreatedEvent(pendingCheckout(userId));
    BillingEventRecord prior =
        new BillingEventRecord(
            UUID.randomUUID(),
            "sandbox",
            event.providerEventId(),
            1,
            event.type(),
            BillingEventStatus.APPLIED,
            event.occurredAt(),
            userId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1_200,
            USD,
            NOW,
            NOW);
    when(provider.verifyWebhook("timestamp", "signature", "{}")).thenReturn(event);
    when(billing.registerReceived(any(BillingEventRecord.class))).thenReturn(false);
    when(billing.findByProviderAndEventId("sandbox", event.providerEventId()))
        .thenReturn(Optional.of(prior));

    WebhookProcessingResult result = service.handleWebhook("timestamp", "signature", "{}");

    assertThat(result).isEqualTo(new WebhookProcessingResult(true, BillingEventStatus.APPLIED));
    verify(billing, never()).saveLedgerEntry(any(BillingLedgerEntry.class));
  }

  @Test
  void outOfOrderPaymentFailureIsRecordedButDoesNotDowngradeTheCurrentSubscription() {
    UUID userId = UUID.randomUUID();
    PaidSubscription current = activeSubscription(userId, NOW.plusSeconds(60));
    PaymentEvent event =
        new PaymentEvent(
            "event-old",
            1,
            BillingEventType.PAYMENT_FAILED,
            NOW.plusSeconds(30),
            null,
            current.providerSubscriptionId(),
            current.providerCustomerId(),
            null,
            null,
            0,
            USD,
            false);
    when(provider.verifyWebhook("timestamp", "signature", "{}")).thenReturn(event);
    when(billing.registerReceived(any(BillingEventRecord.class))).thenReturn(true);
    when(subscriptions.findByProviderSubscriptionIdForUpdate(current.providerSubscriptionId()))
        .thenReturn(Optional.of(current));

    WebhookProcessingResult result = service.handleWebhook("timestamp", "signature", "{}");

    assertThat(result.status()).isEqualTo(BillingEventStatus.IGNORED_STALE);
    verify(subscriptions, never()).update(any(PaidSubscription.class));
    verify(billing, never()).saveLedgerEntry(any(BillingLedgerEntry.class));
  }

  @ParameterizedTest
  @CsvSource({
    "PAYMENT_FAILED,PAST_DUE,0",
    "SUBSCRIPTION_CANCELED,ACTIVE,0",
    "CHARGEBACK,CHARGEBACK,-1200"
  })
  void signedLifecycleEventsApplyExpectedStateAndFinancialLedgerTransition(
      BillingEventType type, SubscriptionStatus expectedStatus, long expectedLedgerAmount) {
    UUID userId = UUID.randomUUID();
    PaidSubscription current = activeSubscription(userId, NOW);
    PaymentEvent event =
        new PaymentEvent(
            "event-" + type,
            1,
            type,
            NOW.plusSeconds(1),
            null,
            current.providerSubscriptionId(),
            current.providerCustomerId(),
            null,
            null,
            type == BillingEventType.CHARGEBACK ? 1_200 : 0,
            USD,
            type == BillingEventType.SUBSCRIPTION_CANCELED);
    when(provider.verifyWebhook("timestamp", "signature", "{}")).thenReturn(event);
    when(billing.registerReceived(any(BillingEventRecord.class))).thenReturn(true);
    when(subscriptions.findByProviderSubscriptionIdForUpdate(current.providerSubscriptionId()))
        .thenReturn(Optional.of(current));

    WebhookProcessingResult result = service.handleWebhook("timestamp", "signature", "{}");

    assertThat(result.status()).isEqualTo(BillingEventStatus.APPLIED);
    ArgumentCaptor<PaidSubscription> subscriptionCaptor =
        ArgumentCaptor.forClass(PaidSubscription.class);
    verify(subscriptions).update(subscriptionCaptor.capture());
    assertThat(subscriptionCaptor.getValue().status()).isEqualTo(expectedStatus);
    assertThat(subscriptionCaptor.getValue().cancelAtPeriodEnd())
        .isEqualTo(type == BillingEventType.SUBSCRIPTION_CANCELED);
    verify(planEntitlementSync)
        .synchronize(
            userId, type == BillingEventType.SUBSCRIPTION_CANCELED ? PlanCode.PRO : PlanCode.FREE);
    if (type == BillingEventType.CHARGEBACK) {
      ArgumentCaptor<BillingLedgerEntry> ledgerCaptor =
          ArgumentCaptor.forClass(BillingLedgerEntry.class);
      verify(billing).saveLedgerEntry(ledgerCaptor.capture());
      assertThat(ledgerCaptor.getValue().amountMinorUnits()).isEqualTo(expectedLedgerAmount);
    } else {
      verify(billing, never()).saveLedgerEntry(any(BillingLedgerEntry.class));
    }
  }

  @Test
  void renewalExtendsThePeriodAndAddsOneChargeEntry() {
    UUID userId = UUID.randomUUID();
    PaidSubscription current = activeSubscription(userId, NOW);
    SubscriptionPeriod renewedPeriod =
        new SubscriptionPeriod(
            current.period().endsAt(), current.period().endsAt().plusSeconds(60));
    PaymentEvent event =
        new PaymentEvent(
            "event-renewal",
            1,
            BillingEventType.SUBSCRIPTION_RENEWED,
            renewedPeriod.startsAt(),
            null,
            current.providerSubscriptionId(),
            current.providerCustomerId(),
            current.planCode(),
            renewedPeriod,
            1_200,
            USD,
            false);
    when(provider.verifyWebhook("timestamp", "signature", "{}")).thenReturn(event);
    when(billing.registerReceived(any(BillingEventRecord.class))).thenReturn(true);
    when(subscriptions.findByProviderSubscriptionIdForUpdate(current.providerSubscriptionId()))
        .thenReturn(Optional.of(current));

    service.handleWebhook("timestamp", "signature", "{}");

    ArgumentCaptor<PaidSubscription> subscriptionCaptor =
        ArgumentCaptor.forClass(PaidSubscription.class);
    verify(subscriptions).update(subscriptionCaptor.capture());
    assertThat(subscriptionCaptor.getValue().period()).isEqualTo(renewedPeriod);
    verify(planEntitlementSync).synchronize(userId, PlanCode.PRO);
    ArgumentCaptor<BillingLedgerEntry> ledgerCaptor =
        ArgumentCaptor.forClass(BillingLedgerEntry.class);
    verify(billing).saveLedgerEntry(ledgerCaptor.capture());
    assertThat(ledgerCaptor.getValue().amountMinorUnits()).isEqualTo(1_200);
  }

  private static SubscriptionPlan proPlan() {
    return new SubscriptionPlan(
        PlanCode.PRO,
        "Pro",
        1_200,
        USD,
        new com.vcut.api.subscription.domain.PlanBenefits(
            BigDecimal.valueOf(600),
            2_000_000,
            7_200,
            50_000_000,
            3,
            5,
            3_840,
            3_840,
            new RetentionPolicy(90, 30, 14, 30, 90, 90, 14)));
  }

  private static CheckoutSession pendingCheckout(UUID userId) {
    return new CheckoutSession(
        UUID.randomUUID(),
        userId,
        PlanCode.PRO,
        CheckoutStatus.PENDING,
        "sandbox",
        "provider-session-1",
        "sandbox-customer-1",
        "https://checkout.example.test/provider-session-1",
        1_200,
        USD,
        NOW.plusSeconds(1_800),
        NOW,
        NOW,
        null);
  }

  private static PaymentEvent checkoutCreatedEvent(CheckoutSession checkout) {
    return new PaymentEvent(
        "event-created-1",
        1,
        BillingEventType.SUBSCRIPTION_CREATED,
        NOW.plusSeconds(2),
        checkout.providerSessionId(),
        "provider-subscription-1",
        checkout.providerCustomerId(),
        checkout.planCode(),
        new SubscriptionPeriod(NOW, NOW.plusSeconds(30L * 24 * 60 * 60)),
        checkout.amountMinorUnits(),
        checkout.currency(),
        false);
  }

  private static PaymentCustomer paymentCustomer(UUID userId, String providerCustomerId) {
    return new PaymentCustomer(UUID.randomUUID(), userId, "sandbox", providerCustomerId, NOW, NOW);
  }

  private static PaidSubscription activeSubscription(UUID userId, Instant lastEventAt) {
    return new PaidSubscription(
        UUID.randomUUID(),
        userId,
        PlanCode.PRO,
        SubscriptionStatus.ACTIVE,
        "sandbox",
        "sandbox-customer-1",
        "provider-subscription-1",
        1_200,
        USD,
        new SubscriptionPeriod(NOW, NOW.plusSeconds(30L * 24 * 60 * 60)),
        false,
        lastEventAt,
        NOW,
        NOW);
  }
}
