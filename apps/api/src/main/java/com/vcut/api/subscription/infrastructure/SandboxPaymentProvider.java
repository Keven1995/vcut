package com.vcut.api.subscription.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vcut.api.shared.errors.ExternalProviderException;
import com.vcut.api.shared.errors.UnauthorizedException;
import com.vcut.api.shared.errors.ValidationException;
import com.vcut.api.subscription.application.PaymentProvider;
import com.vcut.api.subscription.application.SandboxPaymentSimulator;
import com.vcut.api.subscription.domain.BillingEventType;
import com.vcut.api.subscription.domain.CheckoutSession;
import com.vcut.api.subscription.domain.PaidSubscription;
import com.vcut.api.subscription.domain.PaymentEvent;
import com.vcut.api.subscription.domain.SubscriptionPeriod;
import com.vcut.api.usage.domain.PlanCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SandboxPaymentProvider implements PaymentProvider, SandboxPaymentSimulator {

  public static final String PROVIDER_CODE = "sandbox";
  private static final String SIGNATURE_PREFIX = "sha256=";
  private static final int MAX_WEBHOOK_BYTES = 16_384;
  private static final Set<String> EVENT_FIELDS =
      Set.of(
          "eventId",
          "eventVersion",
          "eventType",
          "occurredAt",
          "checkoutSessionId",
          "subscriptionId",
          "customerId",
          "planCode",
          "periodStart",
          "periodEnd",
          "amountMinorUnits",
          "currency",
          "cancelAtPeriodEnd");

  private final SandboxPaymentProperties properties;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  @Autowired
  public SandboxPaymentProvider(SandboxPaymentProperties properties, ObjectMapper objectMapper) {
    this(properties, objectMapper, Clock.systemUTC());
  }

  SandboxPaymentProvider(
      SandboxPaymentProperties properties, ObjectMapper objectMapper, Clock clock) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Override
  public String providerCode() {
    return PROVIDER_CODE;
  }

  @Override
  public boolean enabled() {
    return properties.enabled();
  }

  @Override
  public ProviderCheckout createCheckout(
      UUID checkoutSessionId,
      String existingProviderCustomerId,
      PlanCode planCode,
      long amountMinorUnits,
      Currency currency,
      Instant expiresAt) {
    requireEnabled();
    if (planCode == PlanCode.FREE || amountMinorUnits < 0) {
      throw new ValidationException("Only a paid plan can be checked out.");
    }
    String providerSessionId = "sandbox-checkout-" + UUID.randomUUID();
    String providerCustomerId =
        existingProviderCustomerId == null || existingProviderCustomerId.isBlank()
            ? "sandbox-customer-" + UUID.randomUUID()
            : existingProviderCustomerId;
    String baseUrl = properties.checkoutBaseUrl().toString();
    String checkoutUrl =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "checkoutSessionId=" + checkoutSessionId;
    return new ProviderCheckout(providerSessionId, providerCustomerId, checkoutUrl, expiresAt);
  }

  @Override
  public ProviderCancellation requestCancellation(
      String providerSubscriptionId, Instant periodEnd) {
    if (providerSubscriptionId == null || providerSubscriptionId.isBlank()) {
      throw new ValidationException("Provider subscription reference is required.");
    }
    return new ProviderCancellation(true, true, periodEnd);
  }

  @Override
  public PaymentEvent verifyWebhook(String timestamp, String signature, String rawBody) {
    if (properties.webhookSecret().isBlank()) {
      throw new ExternalProviderException("Sandbox webhook signing secret is not configured.");
    }
    if (rawBody == null
        || rawBody.isBlank()
        || rawBody.getBytes(StandardCharsets.UTF_8).length > MAX_WEBHOOK_BYTES) {
      throw new ValidationException("Payment webhook body is invalid or too large.");
    }
    long timestampSeconds = parseTimestamp(timestamp);
    Instant signedAt;
    try {
      signedAt = Instant.ofEpochSecond(timestampSeconds);
      if (Duration.between(signedAt, clock.instant()).abs().toSeconds()
          > properties.signatureToleranceSeconds()) {
        throw new UnauthorizedException("Payment webhook timestamp is outside the allowed window.");
      }
    } catch (ArithmeticException | java.time.DateTimeException exception) {
      throw new UnauthorizedException("Payment webhook timestamp is invalid.");
    }
    byte[] expected = hmac(timestamp + "." + rawBody);
    byte[] provided = parseSignature(signature);
    if (!MessageDigest.isEqual(expected, provided)) {
      throw new UnauthorizedException("Payment webhook signature is invalid.");
    }
    return parseEvent(rawBody);
  }

  @Override
  public SignedWebhook completeCheckout(CheckoutSession checkoutSession) {
    requireEnabled();
    if (checkoutSession.status() != com.vcut.api.subscription.domain.CheckoutStatus.PENDING
        || checkoutSession.providerSessionId() == null
        || checkoutSession.providerCustomerId() == null) {
      throw new ValidationException("Checkout session is not pending with the sandbox provider.");
    }
    Instant now = clock.instant();
    PaymentEvent event =
        new PaymentEvent(
            UUID.randomUUID().toString(),
            1,
            BillingEventType.SUBSCRIPTION_CREATED,
            now,
            checkoutSession.providerSessionId(),
            "sandbox-subscription-" + UUID.randomUUID(),
            checkoutSession.providerCustomerId(),
            checkoutSession.planCode(),
            new SubscriptionPeriod(now, now.atZone(ZoneOffset.UTC).plusMonths(1).toInstant()),
            checkoutSession.amountMinorUnits(),
            checkoutSession.currency(),
            false);
    return sign(event);
  }

  @Override
  public SignedWebhook cancelAtPeriodEnd(PaidSubscription subscription) {
    requireEnabled();
    Instant now = clock.instant();
    PaymentEvent event =
        new PaymentEvent(
            UUID.randomUUID().toString(),
            1,
            BillingEventType.SUBSCRIPTION_CANCELED,
            now,
            null,
            subscription.providerSubscriptionId(),
            subscription.providerCustomerId(),
            subscription.planCode(),
            subscription.period(),
            0,
            subscription.currency(),
            true);
    return sign(event);
  }

  private SignedWebhook sign(PaymentEvent event) {
    if (properties.webhookSecret().isBlank()) {
      throw new ExternalProviderException("Sandbox webhook signing secret is not configured.");
    }
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("eventId", event.providerEventId());
    payload.put("eventVersion", event.eventVersion());
    payload.put("eventType", event.type().name());
    payload.put("occurredAt", event.occurredAt().toString());
    putOptional(payload, "checkoutSessionId", event.providerCheckoutId());
    putOptional(payload, "subscriptionId", event.providerSubscriptionId());
    putOptional(payload, "customerId", event.providerCustomerId());
    if (event.planCode() != null) {
      payload.put("planCode", event.planCode().name());
    }
    if (event.period() != null) {
      payload.put("periodStart", event.period().startsAt().toString());
      payload.put("periodEnd", event.period().endsAt().toString());
    }
    payload.put("amountMinorUnits", event.amountMinorUnits());
    payload.put("currency", event.currency().getCurrencyCode());
    payload.put("cancelAtPeriodEnd", event.cancelAtPeriodEnd());
    String body = serialize(payload);
    String timestamp = Long.toString(clock.instant().getEpochSecond());
    String signature = SIGNATURE_PREFIX + HexFormat.of().formatHex(hmac(timestamp + "." + body));
    return new SignedWebhook(timestamp, signature, body);
  }

  private PaymentEvent parseEvent(String rawBody) {
    JsonNode payload;
    try {
      payload = objectMapper.readTree(rawBody);
    } catch (JsonProcessingException exception) {
      throw new ValidationException("Payment webhook body is not valid JSON.");
    }
    if (payload == null || !payload.isObject()) {
      throw new ValidationException("Payment webhook body must be a JSON object.");
    }
    payload
        .fieldNames()
        .forEachRemaining(
            field -> {
              if (!EVENT_FIELDS.contains(field)) {
                throw new ValidationException("Unknown payment webhook field.");
              }
            });
    try {
      int eventVersion = requiredInt(payload, "eventVersion");
      if (eventVersion != 1) {
        throw new ValidationException("Unsupported payment webhook version.");
      }
      BillingEventType eventType = BillingEventType.valueOf(requiredText(payload, "eventType"));
      String plan = optionalText(payload, "planCode");
      return new PaymentEvent(
          requiredText(payload, "eventId"),
          eventVersion,
          eventType,
          Instant.parse(requiredText(payload, "occurredAt")),
          optionalText(payload, "checkoutSessionId"),
          optionalText(payload, "subscriptionId"),
          optionalText(payload, "customerId"),
          plan == null ? null : PlanCode.valueOf(plan),
          optionalPeriod(payload),
          optionalLong(payload, "amountMinorUnits", 0),
          Currency.getInstance(requiredText(payload, "currency")),
          optionalBoolean(payload, "cancelAtPeriodEnd", false));
    } catch (IllegalArgumentException | java.time.DateTimeException exception) {
      if (exception instanceof ValidationException validationException) {
        throw validationException;
      }
      throw new ValidationException("Payment webhook fields are invalid.");
    }
  }

  private SubscriptionPeriod optionalPeriod(JsonNode payload) {
    String start = optionalText(payload, "periodStart");
    String end = optionalText(payload, "periodEnd");
    if (start == null && end == null) {
      return null;
    }
    if (start == null || end == null) {
      throw new ValidationException("Payment period requires both start and end.");
    }
    return new SubscriptionPeriod(Instant.parse(start), Instant.parse(end));
  }

  private byte[] hmac(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(
              properties.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.GeneralSecurityException exception) {
      throw new ExternalProviderException("Could not verify sandbox payment webhook.");
    }
  }

  private static byte[] parseSignature(String signature) {
    if (signature == null || signature.isBlank()) {
      throw new UnauthorizedException("Payment webhook signature is required.");
    }
    String value =
        signature.startsWith(SIGNATURE_PREFIX)
            ? signature.substring(SIGNATURE_PREFIX.length())
            : signature;
    try {
      byte[] decoded = HexFormat.of().parseHex(value);
      if (decoded.length != 32) {
        throw new IllegalArgumentException("invalid HMAC length");
      }
      return decoded;
    } catch (IllegalArgumentException exception) {
      throw new UnauthorizedException("Payment webhook signature is invalid.");
    }
  }

  private static long parseTimestamp(String timestamp) {
    try {
      long value = Long.parseLong(timestamp);
      if (value <= 0) {
        throw new NumberFormatException("timestamp must be positive");
      }
      return value;
    } catch (NumberFormatException exception) {
      throw new UnauthorizedException("Payment webhook timestamp is invalid.");
    }
  }

  private static String requiredText(JsonNode payload, String field) {
    String value = optionalText(payload, field);
    if (value == null || value.isBlank()) {
      throw new ValidationException("Payment webhook field is required.");
    }
    return value;
  }

  private static String optionalText(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new ValidationException("Payment webhook text field is invalid.");
    }
    return value.textValue();
  }

  private static int requiredInt(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new ValidationException("Payment webhook integer field is invalid.");
    }
    return value.intValue();
  }

  private static long optionalLong(JsonNode payload, String field, long defaultValue) {
    JsonNode value = payload.get(field);
    if (value == null) {
      return defaultValue;
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new ValidationException("Payment webhook amount is invalid.");
    }
    return value.longValue();
  }

  private static boolean optionalBoolean(JsonNode payload, String field, boolean defaultValue) {
    JsonNode value = payload.get(field);
    if (value == null) {
      return defaultValue;
    }
    if (!value.isBoolean()) {
      throw new ValidationException("Payment webhook boolean field is invalid.");
    }
    return value.booleanValue();
  }

  private String serialize(JsonNode payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException exception) {
      throw new ExternalProviderException("Could not encode sandbox payment webhook.");
    }
  }

  private void requireEnabled() {
    if (!properties.enabled()) {
      throw new ExternalProviderException("Sandbox payment provider is disabled.");
    }
  }

  private static void putOptional(ObjectNode payload, String field, String value) {
    if (value != null) {
      payload.put(field, value);
    }
  }
}
