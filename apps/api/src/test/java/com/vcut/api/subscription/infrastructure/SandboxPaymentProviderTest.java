package com.vcut.api.subscription.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vcut.api.shared.errors.ExternalProviderException;
import com.vcut.api.shared.errors.UnauthorizedException;
import com.vcut.api.subscription.application.PaymentProvider;
import com.vcut.api.subscription.application.SandboxPaymentSimulator.SignedWebhook;
import com.vcut.api.subscription.domain.BillingEventType;
import com.vcut.api.subscription.domain.CheckoutSession;
import com.vcut.api.subscription.domain.CheckoutStatus;
import com.vcut.api.subscription.domain.PaymentEvent;
import com.vcut.api.usage.domain.PlanCode;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SandboxPaymentProviderTest {

  private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

  @Test
  void createsCheckoutWithoutPersistingPaymentInstrumentDataAndSignsVersionedWebhook() {
    String secret = testSecret();
    SandboxPaymentProvider provider = provider(secret, Clock.fixed(NOW, ZoneOffset.UTC), true);
    UUID checkoutId = UUID.randomUUID();
    PaymentProvider.ProviderCheckout providerCheckout =
        provider.createCheckout(
            checkoutId,
            null,
            PlanCode.PRO,
            1_200,
            Currency.getInstance("USD"),
            NOW.plusSeconds(1_800));
    CheckoutSession checkout =
        new CheckoutSession(
            checkoutId,
            UUID.randomUUID(),
            PlanCode.PRO,
            CheckoutStatus.PENDING,
            "sandbox",
            providerCheckout.providerSessionId(),
            providerCheckout.providerCustomerId(),
            providerCheckout.checkoutUrl(),
            1_200,
            Currency.getInstance("USD"),
            providerCheckout.expiresAt(),
            NOW,
            NOW,
            null);

    SignedWebhook signed = provider.completeCheckout(checkout);
    PaymentEvent event =
        provider.verifyWebhook(signed.timestamp(), signed.signature(), signed.body());

    assertThat(providerCheckout.checkoutUrl()).contains(checkoutId.toString());
    assertThat(event.type()).isEqualTo(BillingEventType.SUBSCRIPTION_CREATED);
    assertThat(event.eventVersion()).isEqualTo(1);
    assertThat(event.providerCheckoutId()).isEqualTo(providerCheckout.providerSessionId());
    assertThat(event.providerCustomerId()).isEqualTo(providerCheckout.providerCustomerId());
    assertThat(event.amountMinorUnits()).isEqualTo(1_200);
  }

  @Test
  void rejectsInvalidAndExpiredWebhookSignatures() {
    String secret = testSecret();
    SandboxPaymentProvider currentProvider =
        provider(secret, Clock.fixed(NOW, ZoneOffset.UTC), true);
    CheckoutSession checkout = pendingCheckout();
    SignedWebhook signed = currentProvider.completeCheckout(checkout);

    assertThatThrownBy(
            () ->
                currentProvider.verifyWebhook(
                    signed.timestamp(), "sha256=" + "0".repeat(64), signed.body()))
        .isInstanceOf(UnauthorizedException.class);

    SandboxPaymentProvider oldClockProvider =
        provider(secret, Clock.fixed(NOW.minusSeconds(600), ZoneOffset.UTC), true);
    SignedWebhook expired = oldClockProvider.completeCheckout(checkout);
    assertThatThrownBy(
            () ->
                currentProvider.verifyWebhook(
                    expired.timestamp(), expired.signature(), expired.body()))
        .isInstanceOf(UnauthorizedException.class)
        .hasMessageContaining("outside the allowed window");
  }

  @Test
  void failsClosedWhenSandboxIsDisabledOrItsSigningSecretIsMissing() {
    String secret = testSecret();
    SandboxPaymentProvider disabled = provider(secret, Clock.fixed(NOW, ZoneOffset.UTC), false);
    assertThatThrownBy(() -> disabled.completeCheckout(pendingCheckout()))
        .isInstanceOf(ExternalProviderException.class);
    SignedWebhook alreadyIssued =
        provider(secret, Clock.fixed(NOW, ZoneOffset.UTC), true)
            .completeCheckout(pendingCheckout());
    assertThat(
            disabled
                .verifyWebhook(
                    alreadyIssued.timestamp(), alreadyIssued.signature(), alreadyIssued.body())
                .type())
        .isEqualTo(BillingEventType.SUBSCRIPTION_CREATED);

    assertThatThrownBy(
            () ->
                new SandboxPaymentProperties(
                    true, "", 300, URI.create("http://localhost:3000/dashboard/subscription")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32-byte webhook secret");
  }

  private static SandboxPaymentProvider provider(String secret, Clock clock, boolean enabled) {
    return new SandboxPaymentProvider(
        new SandboxPaymentProperties(
            enabled, secret, 300, URI.create("http://localhost:3000/dashboard/subscription")),
        new ObjectMapper(),
        clock);
  }

  private static String testSecret() {
    return UUID.randomUUID() + UUID.randomUUID().toString();
  }

  private static CheckoutSession pendingCheckout() {
    UUID id = UUID.randomUUID();
    return new CheckoutSession(
        id,
        UUID.randomUUID(),
        PlanCode.PRO,
        CheckoutStatus.PENDING,
        "sandbox",
        "sandbox-checkout-" + id,
        "sandbox-customer-" + id,
        "http://localhost:3000/dashboard/subscription?checkoutSessionId=" + id,
        1_200,
        Currency.getInstance("USD"),
        NOW.plusSeconds(900),
        NOW,
        NOW,
        null);
  }
}
