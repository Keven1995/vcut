package com.vcut.api.subscription.application;

import com.vcut.api.shared.errors.ConflictException;
import com.vcut.api.shared.errors.ExternalProviderException;
import com.vcut.api.shared.errors.ResourceNotFoundException;
import com.vcut.api.shared.errors.ValidationException;
import com.vcut.api.subscription.domain.BillingEventRecord;
import com.vcut.api.subscription.domain.BillingEventStatus;
import com.vcut.api.subscription.domain.BillingLedgerEntry;
import com.vcut.api.subscription.domain.BillingLedgerType;
import com.vcut.api.subscription.domain.CheckoutSession;
import com.vcut.api.subscription.domain.CheckoutStatus;
import com.vcut.api.subscription.domain.PaidSubscription;
import com.vcut.api.subscription.domain.PaymentCustomer;
import com.vcut.api.subscription.domain.PaymentEvent;
import com.vcut.api.subscription.domain.SubscriptionPlan;
import com.vcut.api.subscription.domain.SubscriptionStatus;
import com.vcut.api.usage.domain.PlanCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionApplicationService {

  private static final int HISTORY_LIMIT = 50;

  private final PaidSubscriptionRepository paidSubscriptionRepository;
  private final CheckoutRepository checkoutRepository;
  private final PaymentCustomerRepository paymentCustomerRepository;
  private final BillingRepository billingRepository;
  private final PaymentProvider paymentProvider;
  private final SandboxPaymentSimulator sandboxPaymentSimulator;
  private final SubscriptionPlanCatalog planCatalog;
  private final SubscriptionPricingProvider pricingProvider;
  private final PlanEntitlementSync planEntitlementSync;
  private final Clock clock;

  @Autowired
  public SubscriptionApplicationService(
      PaidSubscriptionRepository paidSubscriptionRepository,
      CheckoutRepository checkoutRepository,
      PaymentCustomerRepository paymentCustomerRepository,
      BillingRepository billingRepository,
      PaymentProvider paymentProvider,
      SandboxPaymentSimulator sandboxPaymentSimulator,
      SubscriptionPlanCatalog planCatalog,
      SubscriptionPricingProvider pricingProvider,
      PlanEntitlementSync planEntitlementSync) {
    this(
        paidSubscriptionRepository,
        checkoutRepository,
        paymentCustomerRepository,
        billingRepository,
        paymentProvider,
        sandboxPaymentSimulator,
        planCatalog,
        pricingProvider,
        planEntitlementSync,
        Clock.systemUTC());
  }

  SubscriptionApplicationService(
      PaidSubscriptionRepository paidSubscriptionRepository,
      CheckoutRepository checkoutRepository,
      PaymentCustomerRepository paymentCustomerRepository,
      BillingRepository billingRepository,
      PaymentProvider paymentProvider,
      SandboxPaymentSimulator sandboxPaymentSimulator,
      SubscriptionPlanCatalog planCatalog,
      SubscriptionPricingProvider pricingProvider,
      PlanEntitlementSync planEntitlementSync,
      Clock clock) {
    this.paidSubscriptionRepository = paidSubscriptionRepository;
    this.checkoutRepository = checkoutRepository;
    this.paymentCustomerRepository = paymentCustomerRepository;
    this.billingRepository = billingRepository;
    this.paymentProvider = paymentProvider;
    this.sandboxPaymentSimulator = sandboxPaymentSimulator;
    this.planCatalog = planCatalog;
    this.pricingProvider = pricingProvider;
    this.planEntitlementSync = planEntitlementSync;
    this.clock = clock;
  }

  @Transactional
  public SubscriptionOverview overview(UUID userId) {
    Instant now = clock.instant();
    checkoutRepository.expirePendingForUser(userId, now);
    List<PaidSubscription> history =
        paidSubscriptionRepository.historyForUser(userId, HISTORY_LIMIT);
    PaidSubscription current = history.isEmpty() ? null : history.getFirst();
    if (current != null
        && (current.status() == SubscriptionStatus.ACTIVE
            || current.status() == SubscriptionStatus.PAST_DUE)
        && !current.period().endsAt().isAfter(now)) {
      current = current.expired(now);
      paidSubscriptionRepository.update(current);
      planEntitlementSync.synchronize(userId, PlanCode.FREE);
    }
    return new SubscriptionOverview(
        sandboxPaymentSimulator.enabled(),
        planCatalog.plans(),
        current,
        checkoutRepository.recentForUser(userId, HISTORY_LIMIT),
        billingRepository.recentEntriesForUser(userId, HISTORY_LIMIT),
        billingRepository.reconcileForUser(userId, pricingProvider.currency()));
  }

  public CheckoutSession createCheckout(UUID userId, PlanCode requestedPlan) {
    if (requestedPlan == null || requestedPlan == PlanCode.FREE) {
      throw new ValidationException("Choose a paid plan to start checkout.");
    }
    Instant now = clock.instant();
    paidSubscriptionRepository
        .findLatestForUser(userId)
        .filter(
            subscription ->
                subscription.status() == SubscriptionStatus.ACTIVE
                    || subscription.status() == SubscriptionStatus.PAST_DUE)
        .ifPresent(
            subscription -> {
              if (!subscription.period().endsAt().isAfter(now)) {
                PaidSubscription expired = subscription.expired(now);
                paidSubscriptionRepository.update(expired);
                planEntitlementSync.synchronize(userId, PlanCode.FREE);
              } else {
                throw new ConflictException("An active or past-due subscription already exists.");
              }
            });
    checkoutRepository.expirePendingForUser(userId, now);
    var pending = checkoutRepository.findPendingForUser(userId, now);
    if (pending.isPresent()) {
      if (pending.get().providerSessionId() == null || pending.get().checkoutUrl() == null) {
        throw new ConflictException("A checkout session is already being prepared.");
      }
      return pending.get();
    }

    SubscriptionPlan plan = planCatalog.planFor(requestedPlan);
    if (plan.monthlyPriceMinorUnits() == 0 && !sandboxPaymentSimulator.enabled()) {
      throw new ValidationException("The Pro plan price is not configured for live checkout.");
    }
    CheckoutSession checkout =
        new CheckoutSession(
            UUID.randomUUID(),
            userId,
            plan.code(),
            CheckoutStatus.PENDING,
            paymentProvider.providerCode(),
            null,
            null,
            null,
            plan.monthlyPriceMinorUnits(),
            plan.currency(),
            now.plus(pricingProvider.checkoutTtl()),
            now,
            now,
            null);
    try {
      checkoutRepository.save(checkout);
    } catch (DataIntegrityViolationException exception) {
      throw new ConflictException("A checkout session is already pending for this user.");
    }

    try {
      String knownCustomerId =
          paymentCustomerRepository
              .findByUserAndProvider(userId, paymentProvider.providerCode())
              .map(PaymentCustomer::providerCustomerId)
              .orElse(null);
      PaymentProvider.ProviderCheckout providerCheckout =
          paymentProvider.createCheckout(
              checkout.id(),
              knownCustomerId,
              plan.code(),
              plan.monthlyPriceMinorUnits(),
              plan.currency(),
              checkout.expiresAt());
      checkout =
          checkout.attachProviderDetails(
              providerCheckout.providerSessionId(),
              providerCheckout.providerCustomerId(),
              providerCheckout.checkoutUrl(),
              providerCheckout.expiresAt(),
              clock.instant());
      paymentCustomerRepository.saveOrUpdate(
          new PaymentCustomer(
              UUID.randomUUID(),
              userId,
              paymentProvider.providerCode(),
              providerCheckout.providerCustomerId(),
              now,
              clock.instant()));
      checkoutRepository.update(checkout);
      return checkout;
    } catch (RuntimeException exception) {
      checkoutRepository.update(checkout.fail(clock.instant()));
      if (exception instanceof ExternalProviderException providerException) {
        throw providerException;
      }
      throw new ExternalProviderException("Payment provider could not create checkout.");
    }
  }

  @Transactional
  public CheckoutSession confirmSandboxCheckout(UUID userId, UUID checkoutId) {
    CheckoutSession checkout =
        checkoutRepository
            .findCheckoutByIdForUserForUpdate(checkoutId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Checkout session not found."));
    if (!checkout.provider().equals(paymentProvider.providerCode())) {
      throw new ConflictException("Checkout session belongs to another payment provider.");
    }
    SandboxPaymentSimulator.SignedWebhook signed =
        sandboxPaymentSimulator.completeCheckout(checkout);
    processSignedWebhook(signed.timestamp(), signed.signature(), signed.body());
    return checkoutRepository
        .findCheckoutByIdForUserForUpdate(checkoutId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("Checkout session not found."));
  }

  @Transactional
  public PaidSubscription cancelAtPeriodEnd(UUID userId, UUID subscriptionId) {
    PaidSubscription subscription =
        paidSubscriptionRepository
            .findSubscriptionByIdForUserForUpdate(subscriptionId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Subscription not found."));
    if (subscription.status() != SubscriptionStatus.ACTIVE
        && subscription.status() != SubscriptionStatus.PAST_DUE) {
      throw new ConflictException("Subscription cannot be canceled in its current state.");
    }
    if (subscription.cancelAtPeriodEnd()) {
      return subscription;
    }
    PaymentProvider.ProviderCancellation cancellation =
        paymentProvider.requestCancellation(
            subscription.providerSubscriptionId(), subscription.period().endsAt());
    if (!cancellation.accepted() || !cancellation.atPeriodEnd()) {
      throw new ExternalProviderException(
          "Payment provider did not accept subscription cancellation.");
    }
    if (sandboxPaymentSimulator.enabled()) {
      SandboxPaymentSimulator.SignedWebhook signed =
          sandboxPaymentSimulator.cancelAtPeriodEnd(subscription);
      processSignedWebhook(signed.timestamp(), signed.signature(), signed.body());
      return paidSubscriptionRepository
          .findSubscriptionByIdForUserForUpdate(subscriptionId, userId)
          .orElseThrow(() -> new ResourceNotFoundException("Subscription not found."));
    }
    PaidSubscription scheduled = subscription.scheduleCancellation(clock.instant());
    paidSubscriptionRepository.update(scheduled);
    synchronizeEntitlement(scheduled, clock.instant());
    return scheduled;
  }

  @Transactional
  public WebhookProcessingResult handleWebhook(String timestamp, String signature, String rawBody) {
    return processSignedWebhook(timestamp, signature, rawBody);
  }

  private WebhookProcessingResult processSignedWebhook(
      String timestamp, String signature, String rawBody) {
    PaymentEvent event = paymentProvider.verifyWebhook(timestamp, signature, rawBody);
    Instant receivedAt = clock.instant();
    long initialAmount = event.amountMinorUnits();
    BillingEventRecord received =
        new BillingEventRecord(
            UUID.randomUUID(),
            paymentProvider.providerCode(),
            event.providerEventId(),
            event.eventVersion(),
            event.type(),
            BillingEventStatus.PROCESSING,
            event.occurredAt(),
            null,
            null,
            null,
            initialAmount,
            event.currency(),
            receivedAt,
            null);
    if (!billingRepository.registerReceived(received)) {
      BillingEventRecord prior =
          billingRepository
              .findByProviderAndEventId(paymentProvider.providerCode(), event.providerEventId())
              .orElseThrow(
                  () -> new IllegalStateException("billing event duplicate was not found"));
      return new WebhookProcessingResult(true, prior.status());
    }
    return applyEvent(received, event);
  }

  private WebhookProcessingResult applyEvent(BillingEventRecord received, PaymentEvent event) {
    return switch (event.type()) {
      case SUBSCRIPTION_CREATED -> applySubscriptionCreated(received, event);
      case SUBSCRIPTION_RENEWED -> applySubscriptionRenewal(received, event);
      case PAYMENT_FAILED -> applyPaymentFailure(received, event);
      case SUBSCRIPTION_CANCELED -> applyCancellation(received, event);
      case CHARGEBACK -> applyChargeback(received, event);
    };
  }

  private WebhookProcessingResult applySubscriptionCreated(
      BillingEventRecord received, PaymentEvent event) {
    CheckoutSession checkout =
        checkoutRepository
            .findByProviderSessionIdForUpdate(event.providerCheckoutId())
            .orElseThrow(() -> new ResourceNotFoundException("Checkout session was not found."));
    if (checkout.status() != CheckoutStatus.PENDING
        || !checkout.provider().equals(paymentProvider.providerCode())
        || !checkout.planCode().equals(event.planCode())
        || !checkout.providerCustomerId().equals(event.providerCustomerId())
        || checkout.amountMinorUnits() != event.amountMinorUnits()
        || !checkout.currency().equals(event.currency())
        || event.occurredAt().isBefore(checkout.createdAt())
        || !event.occurredAt().isBefore(checkout.expiresAt())) {
      throw new ConflictException("Payment event does not match the pending checkout.");
    }
    paidSubscriptionRepository
        .findLatestForUser(checkout.userId())
        .ifPresent(
            current -> {
              if ((current.status() == SubscriptionStatus.ACTIVE
                      || current.status() == SubscriptionStatus.PAST_DUE)
                  && current.period().endsAt().isAfter(event.occurredAt())) {
                throw new ConflictException("User already has an active subscription.");
              }
              if ((current.status() == SubscriptionStatus.ACTIVE
                      || current.status() == SubscriptionStatus.PAST_DUE)
                  && !current.period().endsAt().isAfter(event.occurredAt())) {
                paidSubscriptionRepository.update(current.expired(event.occurredAt()));
              }
            });
    PaidSubscription subscription =
        new PaidSubscription(
            UUID.randomUUID(),
            checkout.userId(),
            checkout.planCode(),
            SubscriptionStatus.ACTIVE,
            checkout.provider(),
            checkout.providerCustomerId(),
            requiredProviderSubscriptionId(event),
            event.amountMinorUnits(),
            event.currency(),
            event.period(),
            false,
            event.occurredAt(),
            event.occurredAt(),
            event.occurredAt());
    paidSubscriptionRepository.save(subscription);
    checkoutRepository.update(checkout.complete(event.occurredAt()));
    planEntitlementSync.synchronize(checkout.userId(), subscription.planCode());
    addLedgerEntry(
        received, subscription, BillingLedgerType.CHARGE, event.amountMinorUnits(), event);
    completeEvent(
        received,
        event,
        BillingEventStatus.APPLIED,
        checkout.userId(),
        checkout.id(),
        subscription.id(),
        event.amountMinorUnits());
    return new WebhookProcessingResult(false, BillingEventStatus.APPLIED);
  }

  private WebhookProcessingResult applySubscriptionRenewal(
      BillingEventRecord received, PaymentEvent event) {
    PaidSubscription subscription = findSubscriptionForEvent(event);
    if (!subscription.acceptsEventAt(event.occurredAt())) {
      return ignoreStaleEvent(received, event, subscription);
    }
    if (event.planCode() != subscription.planCode()
        || !subscription.providerCustomerId().equals(event.providerCustomerId())
        || !subscription.currency().equals(event.currency())) {
      throw new ConflictException("Renewal event does not match the active subscription.");
    }
    PaidSubscription renewed =
        subscription.renewed(
            event.period(), event.amountMinorUnits(), event.currency(), event.occurredAt());
    paidSubscriptionRepository.update(renewed);
    synchronizeEntitlement(renewed, event.occurredAt());
    addLedgerEntry(received, renewed, BillingLedgerType.CHARGE, event.amountMinorUnits(), event);
    completeEvent(
        received,
        event,
        BillingEventStatus.APPLIED,
        subscription.userId(),
        null,
        subscription.id(),
        event.amountMinorUnits());
    return new WebhookProcessingResult(false, BillingEventStatus.APPLIED);
  }

  private WebhookProcessingResult applyPaymentFailure(
      BillingEventRecord received, PaymentEvent event) {
    PaidSubscription subscription = findSubscriptionForEvent(event);
    if (!subscription.acceptsEventAt(event.occurredAt())) {
      return ignoreStaleEvent(received, event, subscription);
    }
    PaidSubscription pastDue = subscription.paymentFailed(event.occurredAt());
    paidSubscriptionRepository.update(pastDue);
    synchronizeEntitlement(pastDue, event.occurredAt());
    completeEvent(
        received,
        event,
        BillingEventStatus.APPLIED,
        subscription.userId(),
        null,
        subscription.id(),
        0);
    return new WebhookProcessingResult(false, BillingEventStatus.APPLIED);
  }

  private WebhookProcessingResult applyCancellation(
      BillingEventRecord received, PaymentEvent event) {
    PaidSubscription subscription = findSubscriptionForEvent(event);
    if (!subscription.acceptsEventAt(event.occurredAt())) {
      return ignoreStaleEvent(received, event, subscription);
    }
    if (event.providerCustomerId() != null
        && !subscription.providerCustomerId().equals(event.providerCustomerId())) {
      throw new ConflictException("Cancellation event does not match the subscription owner.");
    }
    PaidSubscription updated =
        event.cancelAtPeriodEnd()
            ? subscription.scheduleCancellation(event.occurredAt())
            : subscription.canceled(event.occurredAt());
    paidSubscriptionRepository.update(updated);
    synchronizeEntitlement(updated, event.occurredAt());
    completeEvent(
        received,
        event,
        BillingEventStatus.APPLIED,
        subscription.userId(),
        null,
        subscription.id(),
        0);
    return new WebhookProcessingResult(false, BillingEventStatus.APPLIED);
  }

  private WebhookProcessingResult applyChargeback(BillingEventRecord received, PaymentEvent event) {
    PaidSubscription subscription = findSubscriptionForEvent(event);
    if (!subscription.acceptsEventAt(event.occurredAt())) {
      return ignoreStaleEvent(received, event, subscription);
    }
    if (event.providerCustomerId() != null
        && !subscription.providerCustomerId().equals(event.providerCustomerId())) {
      throw new ConflictException("Chargeback event does not match the subscription owner.");
    }
    long amount =
        event.amountMinorUnits() == 0
            ? subscription.monthlyPriceMinorUnits()
            : event.amountMinorUnits();
    long negativeAmount = Math.negateExact(amount);
    PaidSubscription chargedBack = subscription.chargedBack(event.occurredAt());
    paidSubscriptionRepository.update(chargedBack);
    synchronizeEntitlement(chargedBack, event.occurredAt());
    addLedgerEntry(received, chargedBack, BillingLedgerType.CHARGEBACK, negativeAmount, event);
    completeEvent(
        received,
        event,
        BillingEventStatus.APPLIED,
        subscription.userId(),
        null,
        subscription.id(),
        negativeAmount);
    return new WebhookProcessingResult(false, BillingEventStatus.APPLIED);
  }

  private WebhookProcessingResult ignoreStaleEvent(
      BillingEventRecord received, PaymentEvent event, PaidSubscription subscription) {
    completeEvent(
        received,
        event,
        BillingEventStatus.IGNORED_STALE,
        subscription.userId(),
        null,
        subscription.id(),
        0);
    return new WebhookProcessingResult(false, BillingEventStatus.IGNORED_STALE);
  }

  private PaidSubscription findSubscriptionForEvent(PaymentEvent event) {
    PaidSubscription subscription =
        paidSubscriptionRepository
            .findByProviderSubscriptionIdForUpdate(requiredProviderSubscriptionId(event))
            .orElseThrow(
                () ->
                    new ResourceNotFoundException("Subscription for payment event was not found."));
    if (event.providerCustomerId() != null
        && !subscription.providerCustomerId().equals(event.providerCustomerId())) {
      throw new ConflictException("Payment event does not match the subscription owner.");
    }
    if (!subscription.currency().equals(event.currency())) {
      throw new ConflictException("Payment event currency does not match the subscription.");
    }
    return subscription;
  }

  private void addLedgerEntry(
      BillingEventRecord received,
      PaidSubscription subscription,
      BillingLedgerType type,
      long amount,
      PaymentEvent event) {
    billingRepository.saveLedgerEntry(
        new BillingLedgerEntry(
            UUID.randomUUID(),
            subscription.userId(),
            subscription.id(),
            received.id(),
            type,
            amount,
            event.currency(),
            event.occurredAt()));
  }

  private void completeEvent(
      BillingEventRecord received,
      PaymentEvent event,
      BillingEventStatus status,
      UUID userId,
      UUID checkoutId,
      UUID subscriptionId,
      long signedAmount) {
    billingRepository.completeEvent(
        new BillingEventRecord(
            received.id(),
            received.provider(),
            received.providerEventId(),
            received.eventVersion(),
            received.type(),
            status,
            event.occurredAt(),
            userId,
            checkoutId,
            subscriptionId,
            signedAmount,
            event.currency(),
            received.receivedAt(),
            clock.instant()));
  }

  private static String requiredProviderSubscriptionId(PaymentEvent event) {
    if (event.providerSubscriptionId() == null || event.providerSubscriptionId().isBlank()) {
      throw new ValidationException("Payment event subscription reference is required.");
    }
    return event.providerSubscriptionId();
  }

  private void synchronizeEntitlement(PaidSubscription subscription, Instant at) {
    PlanCode currentPlan = subscription.isActiveAt(at) ? subscription.planCode() : PlanCode.FREE;
    planEntitlementSync.synchronize(subscription.userId(), currentPlan);
  }
}
