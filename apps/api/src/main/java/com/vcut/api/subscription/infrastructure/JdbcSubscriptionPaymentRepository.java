package com.vcut.api.subscription.infrastructure;

import com.vcut.api.subscription.application.BillingRepository;
import com.vcut.api.subscription.application.CheckoutRepository;
import com.vcut.api.subscription.application.PaidSubscriptionRepository;
import com.vcut.api.subscription.application.PaymentCustomerRepository;
import com.vcut.api.subscription.domain.BillingEventRecord;
import com.vcut.api.subscription.domain.BillingEventStatus;
import com.vcut.api.subscription.domain.BillingEventType;
import com.vcut.api.subscription.domain.BillingLedgerEntry;
import com.vcut.api.subscription.domain.BillingLedgerType;
import com.vcut.api.subscription.domain.BillingReconciliation;
import com.vcut.api.subscription.domain.CheckoutSession;
import com.vcut.api.subscription.domain.CheckoutStatus;
import com.vcut.api.subscription.domain.PaidSubscription;
import com.vcut.api.subscription.domain.PaymentCustomer;
import com.vcut.api.subscription.domain.SubscriptionPeriod;
import com.vcut.api.subscription.domain.SubscriptionStatus;
import com.vcut.api.usage.domain.PlanCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSubscriptionPaymentRepository
    implements PaidSubscriptionRepository,
        CheckoutRepository,
        PaymentCustomerRepository,
        BillingRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcSubscriptionPaymentRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<PaidSubscription> findLatestForUser(UUID userId) {
    return jdbcTemplate
        .query(
            "SELECT * FROM subscriptions WHERE user_id = ? "
                + "ORDER BY COALESCE(last_event_at, updated_at) DESC, created_at DESC LIMIT 1",
            JdbcSubscriptionPaymentRepository::mapSubscription,
            userId)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<PaidSubscription> findByProviderSubscriptionIdForUpdate(
      String providerSubscriptionId) {
    return jdbcTemplate
        .query(
            "SELECT * FROM subscriptions WHERE provider_subscription_id = ? FOR UPDATE",
            JdbcSubscriptionPaymentRepository::mapSubscription,
            providerSubscriptionId)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<PaidSubscription> findSubscriptionByIdForUserForUpdate(
      UUID subscriptionId, UUID userId) {
    return jdbcTemplate
        .query(
            "SELECT * FROM subscriptions WHERE id = ? AND user_id = ? FOR UPDATE",
            JdbcSubscriptionPaymentRepository::mapSubscription,
            subscriptionId,
            userId)
        .stream()
        .findFirst();
  }

  @Override
  public PaidSubscription save(PaidSubscription subscription) {
    jdbcTemplate.update(
        "INSERT INTO subscriptions (id, user_id, plan_code, status, period_start, period_end, "
            + "created_at, updated_at, provider_code, provider_customer_id, provider_subscription_id, "
            + "monthly_price_minor_units, currency, cancel_at_period_end, last_event_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        subscription.id(),
        subscription.userId(),
        subscription.planCode().name(),
        subscription.status().name(),
        Timestamp.from(subscription.period().startsAt()),
        Timestamp.from(subscription.period().endsAt()),
        Timestamp.from(subscription.createdAt()),
        Timestamp.from(subscription.updatedAt()),
        subscription.provider(),
        subscription.providerCustomerId(),
        subscription.providerSubscriptionId(),
        subscription.monthlyPriceMinorUnits(),
        subscription.currency().getCurrencyCode(),
        subscription.cancelAtPeriodEnd(),
        timestamp(subscription.lastEventAt()));
    return subscription;
  }

  @Override
  public void update(PaidSubscription subscription) {
    int updated =
        jdbcTemplate.update(
            "UPDATE subscriptions SET plan_code = ?, status = ?, period_start = ?, period_end = ?, "
                + "updated_at = ?, provider_code = ?, provider_customer_id = ?, "
                + "provider_subscription_id = ?, monthly_price_minor_units = ?, currency = ?, "
                + "cancel_at_period_end = ?, last_event_at = ? WHERE id = ? AND user_id = ?",
            subscription.planCode().name(),
            subscription.status().name(),
            Timestamp.from(subscription.period().startsAt()),
            Timestamp.from(subscription.period().endsAt()),
            Timestamp.from(subscription.updatedAt()),
            subscription.provider(),
            subscription.providerCustomerId(),
            subscription.providerSubscriptionId(),
            subscription.monthlyPriceMinorUnits(),
            subscription.currency().getCurrencyCode(),
            subscription.cancelAtPeriodEnd(),
            timestamp(subscription.lastEventAt()),
            subscription.id(),
            subscription.userId());
    requireOne(updated, "subscription");
  }

  @Override
  public List<PaidSubscription> historyForUser(UUID userId, int limit) {
    return jdbcTemplate.query(
        "SELECT * FROM subscriptions WHERE user_id = ? " + "ORDER BY created_at DESC LIMIT ?",
        JdbcSubscriptionPaymentRepository::mapSubscription,
        userId,
        limit);
  }

  @Override
  public CheckoutSession save(CheckoutSession checkoutSession) {
    jdbcTemplate.update(
        "INSERT INTO checkout_sessions (id, user_id, plan_code, checkout_status, provider_code, "
            + "provider_session_id, provider_customer_id, checkout_url, amount_minor_units, currency, "
            + "expires_at, created_at, updated_at, completed_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        checkoutSession.id(),
        checkoutSession.userId(),
        checkoutSession.planCode().name(),
        checkoutSession.status().name(),
        checkoutSession.provider(),
        checkoutSession.providerSessionId(),
        checkoutSession.providerCustomerId(),
        checkoutSession.checkoutUrl(),
        checkoutSession.amountMinorUnits(),
        checkoutSession.currency().getCurrencyCode(),
        Timestamp.from(checkoutSession.expiresAt()),
        Timestamp.from(checkoutSession.createdAt()),
        Timestamp.from(checkoutSession.updatedAt()),
        timestamp(checkoutSession.completedAt()));
    return checkoutSession;
  }

  @Override
  public void update(CheckoutSession checkoutSession) {
    int updated =
        jdbcTemplate.update(
            "UPDATE checkout_sessions SET checkout_status = ?, provider_session_id = ?, "
                + "provider_customer_id = ?, checkout_url = ?, expires_at = ?, updated_at = ?, completed_at = ? "
                + "WHERE id = ? AND user_id = ?",
            checkoutSession.status().name(),
            checkoutSession.providerSessionId(),
            checkoutSession.providerCustomerId(),
            checkoutSession.checkoutUrl(),
            Timestamp.from(checkoutSession.expiresAt()),
            Timestamp.from(checkoutSession.updatedAt()),
            timestamp(checkoutSession.completedAt()),
            checkoutSession.id(),
            checkoutSession.userId());
    requireOne(updated, "checkout session");
  }

  @Override
  public void expirePendingForUser(UUID userId, Instant now) {
    jdbcTemplate.update(
        "UPDATE checkout_sessions SET checkout_status = 'EXPIRED', updated_at = ? "
            + "WHERE user_id = ? AND checkout_status = 'PENDING' AND expires_at <= ?",
        Timestamp.from(now),
        userId,
        Timestamp.from(now));
  }

  @Override
  public Optional<CheckoutSession> findCheckoutByIdForUserForUpdate(UUID checkoutId, UUID userId) {
    return jdbcTemplate
        .query(
            "SELECT * FROM checkout_sessions WHERE id = ? AND user_id = ? FOR UPDATE",
            JdbcSubscriptionPaymentRepository::mapCheckout,
            checkoutId,
            userId)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<CheckoutSession> findByProviderSessionIdForUpdate(String providerSessionId) {
    return jdbcTemplate
        .query(
            "SELECT * FROM checkout_sessions WHERE provider_session_id = ? FOR UPDATE",
            JdbcSubscriptionPaymentRepository::mapCheckout,
            providerSessionId)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<CheckoutSession> findPendingForUser(UUID userId, java.time.Instant now) {
    return jdbcTemplate
        .query(
            "SELECT * FROM checkout_sessions WHERE user_id = ? AND checkout_status = 'PENDING' "
                + "AND expires_at > ? ORDER BY created_at DESC LIMIT 1",
            JdbcSubscriptionPaymentRepository::mapCheckout,
            userId,
            Timestamp.from(now))
        .stream()
        .findFirst();
  }

  @Override
  public List<CheckoutSession> recentForUser(UUID userId, int limit) {
    return jdbcTemplate.query(
        "SELECT * FROM checkout_sessions WHERE user_id = ? ORDER BY created_at DESC LIMIT ?",
        JdbcSubscriptionPaymentRepository::mapCheckout,
        userId,
        limit);
  }

  @Override
  public Optional<PaymentCustomer> findByUserAndProvider(UUID userId, String provider) {
    return jdbcTemplate
        .query(
            "SELECT * FROM payment_customers WHERE user_id = ? AND provider_code = ?",
            JdbcSubscriptionPaymentRepository::mapPaymentCustomer,
            userId,
            provider)
        .stream()
        .findFirst();
  }

  @Override
  public PaymentCustomer saveOrUpdate(PaymentCustomer paymentCustomer) {
    jdbcTemplate.update(
        "INSERT INTO payment_customers (id, user_id, provider_code, provider_customer_id, "
            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (user_id, provider_code) DO UPDATE SET "
            + "provider_customer_id = EXCLUDED.provider_customer_id, updated_at = EXCLUDED.updated_at",
        paymentCustomer.id(),
        paymentCustomer.userId(),
        paymentCustomer.provider(),
        paymentCustomer.providerCustomerId(),
        Timestamp.from(paymentCustomer.createdAt()),
        Timestamp.from(paymentCustomer.updatedAt()));
    return findByUserAndProvider(paymentCustomer.userId(), paymentCustomer.provider())
        .orElseThrow(() -> new IllegalStateException("payment customer disappeared after upsert"));
  }

  @Override
  public boolean registerReceived(BillingEventRecord eventRecord) {
    int inserted =
        jdbcTemplate.update(
            "INSERT INTO billing_events (id, provider_code, provider_event_id, event_version, "
                + "event_type, processing_status, occurred_at, user_id, checkout_session_id, "
                + "subscription_id, amount_minor_units, currency, received_at, processed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (provider_code, provider_event_id) DO NOTHING",
            eventRecord.id(),
            eventRecord.provider(),
            eventRecord.providerEventId(),
            eventRecord.eventVersion(),
            eventRecord.type().name(),
            eventRecord.status().name(),
            Timestamp.from(eventRecord.occurredAt()),
            eventRecord.userId(),
            eventRecord.checkoutSessionId(),
            eventRecord.subscriptionId(),
            eventRecord.amountMinorUnits(),
            eventRecord.currency().getCurrencyCode(),
            Timestamp.from(eventRecord.receivedAt()),
            timestamp(eventRecord.processedAt()));
    return inserted == 1;
  }

  @Override
  public Optional<BillingEventRecord> findByProviderAndEventId(
      String provider, String providerEventId) {
    return jdbcTemplate
        .query(
            "SELECT * FROM billing_events WHERE provider_code = ? AND provider_event_id = ?",
            JdbcSubscriptionPaymentRepository::mapBillingEvent,
            provider,
            providerEventId)
        .stream()
        .findFirst();
  }

  @Override
  public void completeEvent(BillingEventRecord eventRecord) {
    int updated =
        jdbcTemplate.update(
            "UPDATE billing_events SET processing_status = ?, user_id = ?, checkout_session_id = ?, "
                + "subscription_id = ?, amount_minor_units = ?, currency = ?, processed_at = ? "
                + "WHERE provider_code = ? AND provider_event_id = ? AND processing_status = 'PROCESSING'",
            eventRecord.status().name(),
            eventRecord.userId(),
            eventRecord.checkoutSessionId(),
            eventRecord.subscriptionId(),
            eventRecord.amountMinorUnits(),
            eventRecord.currency().getCurrencyCode(),
            timestamp(eventRecord.processedAt()),
            eventRecord.provider(),
            eventRecord.providerEventId());
    requireOne(updated, "billing event");
  }

  @Override
  public void saveLedgerEntry(BillingLedgerEntry entry) {
    jdbcTemplate.update(
        "INSERT INTO billing_ledger_entries (id, user_id, subscription_id, billing_event_id, "
            + "entry_type, amount_minor_units, currency, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        entry.id(),
        entry.userId(),
        entry.subscriptionId(),
        entry.billingEventId(),
        entry.type().name(),
        entry.amountMinorUnits(),
        entry.currency().getCurrencyCode(),
        Timestamp.from(entry.createdAt()));
  }

  @Override
  public List<BillingLedgerEntry> recentEntriesForUser(UUID userId, int limit) {
    return jdbcTemplate.query(
        "SELECT * FROM billing_ledger_entries WHERE user_id = ? ORDER BY created_at DESC LIMIT ?",
        JdbcSubscriptionPaymentRepository::mapLedgerEntry,
        userId,
        limit);
  }

  @Override
  public BillingReconciliation reconcileForUser(UUID userId, Currency currency) {
    FinancialTotal providerTotal =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) AS item_count, COALESCE(SUM(amount_minor_units), 0) AS amount_total "
                + "FROM billing_events WHERE user_id = ? AND currency = ? AND processing_status = 'APPLIED' "
                + "AND event_type IN ('SUBSCRIPTION_CREATED', 'SUBSCRIPTION_RENEWED', 'CHARGEBACK')",
            (resultSet, rowNumber) ->
                new FinancialTotal(
                    resultSet.getLong("item_count"), resultSet.getLong("amount_total")),
            userId,
            currency.getCurrencyCode());
    FinancialTotal ledgerTotal =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) AS item_count, COALESCE(SUM(amount_minor_units), 0) AS amount_total "
                + "FROM billing_ledger_entries WHERE user_id = ? AND currency = ?",
            (resultSet, rowNumber) ->
                new FinancialTotal(
                    resultSet.getLong("item_count"), resultSet.getLong("amount_total")),
            userId,
            currency.getCurrencyCode());
    return new BillingReconciliation(
        currency,
        providerTotal.count(),
        ledgerTotal.count(),
        providerTotal.amount(),
        ledgerTotal.amount());
  }

  private static PaidSubscription mapSubscription(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new PaidSubscription(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("user_id", UUID.class),
        PlanCode.valueOf(resultSet.getString("plan_code")),
        SubscriptionStatus.valueOf(resultSet.getString("status")),
        resultSet.getString("provider_code"),
        resultSet.getString("provider_customer_id"),
        resultSet.getString("provider_subscription_id"),
        resultSet.getLong("monthly_price_minor_units"),
        Currency.getInstance(resultSet.getString("currency").trim()),
        new SubscriptionPeriod(
            resultSet.getTimestamp("period_start").toInstant(),
            resultSet.getTimestamp("period_end").toInstant()),
        resultSet.getBoolean("cancel_at_period_end"),
        nullableInstant(resultSet, "last_event_at"),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant());
  }

  private static CheckoutSession mapCheckout(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new CheckoutSession(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("user_id", UUID.class),
        PlanCode.valueOf(resultSet.getString("plan_code")),
        CheckoutStatus.valueOf(resultSet.getString("checkout_status")),
        resultSet.getString("provider_code"),
        resultSet.getString("provider_session_id"),
        resultSet.getString("provider_customer_id"),
        resultSet.getString("checkout_url"),
        resultSet.getLong("amount_minor_units"),
        Currency.getInstance(resultSet.getString("currency").trim()),
        resultSet.getTimestamp("expires_at").toInstant(),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant(),
        nullableInstant(resultSet, "completed_at"));
  }

  private static PaymentCustomer mapPaymentCustomer(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new PaymentCustomer(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("user_id", UUID.class),
        resultSet.getString("provider_code"),
        resultSet.getString("provider_customer_id"),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant());
  }

  private static BillingLedgerEntry mapLedgerEntry(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new BillingLedgerEntry(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("user_id", UUID.class),
        resultSet.getObject("subscription_id", UUID.class),
        resultSet.getObject("billing_event_id", UUID.class),
        BillingLedgerType.valueOf(resultSet.getString("entry_type")),
        resultSet.getLong("amount_minor_units"),
        Currency.getInstance(resultSet.getString("currency").trim()),
        resultSet.getTimestamp("created_at").toInstant());
  }

  private static BillingEventRecord mapBillingEvent(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new BillingEventRecord(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("provider_code"),
        resultSet.getString("provider_event_id"),
        resultSet.getInt("event_version"),
        BillingEventType.valueOf(resultSet.getString("event_type")),
        BillingEventStatus.valueOf(resultSet.getString("processing_status")),
        resultSet.getTimestamp("occurred_at").toInstant(),
        resultSet.getObject("user_id", UUID.class),
        resultSet.getObject("checkout_session_id", UUID.class),
        resultSet.getObject("subscription_id", UUID.class),
        resultSet.getLong("amount_minor_units"),
        Currency.getInstance(resultSet.getString("currency").trim()),
        resultSet.getTimestamp("received_at").toInstant(),
        nullableInstant(resultSet, "processed_at"));
  }

  private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
    Timestamp value = resultSet.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Timestamp timestamp(java.time.Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static void requireOne(int updated, String resource) {
    if (updated != 1) {
      throw new IllegalArgumentException(resource + " not found");
    }
  }

  private record FinancialTotal(long count, long amount) {}
}
