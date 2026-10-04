ALTER TABLE subscriptions
    DROP CONSTRAINT subscriptions_status_check;

ALTER TABLE subscriptions
    ADD COLUMN provider_code VARCHAR(32) NOT NULL DEFAULT 'legacy',
    ADD COLUMN provider_customer_id VARCHAR(255),
    ADD COLUMN provider_subscription_id VARCHAR(255),
    ADD COLUMN monthly_price_minor_units BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN currency CHAR(3) NOT NULL DEFAULT 'USD',
    ADD COLUMN cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN last_event_at TIMESTAMPTZ;

ALTER TABLE subscriptions
    ADD CONSTRAINT subscriptions_status_check
        CHECK (status IN ('ACTIVE', 'PAST_DUE', 'CANCELED', 'EXPIRED', 'CHARGEBACK')),
    ADD CONSTRAINT subscriptions_price_check CHECK (monthly_price_minor_units >= 0),
    ADD CONSTRAINT subscriptions_currency_check CHECK (currency ~ '^[A-Z]{3}$');

CREATE UNIQUE INDEX subscriptions_provider_subscription_unique_idx
    ON subscriptions (provider_code, provider_subscription_id)
    WHERE provider_subscription_id IS NOT NULL;

CREATE TABLE payment_customers (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider_code VARCHAR(32) NOT NULL,
    provider_customer_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT payment_customers_user_provider_unique UNIQUE (user_id, provider_code),
    CONSTRAINT payment_customers_provider_id_unique UNIQUE (provider_code, provider_customer_id)
);

CREATE TABLE checkout_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    plan_code VARCHAR(16) NOT NULL,
    checkout_status VARCHAR(16) NOT NULL,
    provider_code VARCHAR(32) NOT NULL,
    provider_session_id VARCHAR(255),
    provider_customer_id VARCHAR(255),
    checkout_url VARCHAR(2048),
    amount_minor_units BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT checkout_sessions_plan_check CHECK (plan_code IN ('FREE', 'PRO')),
    CONSTRAINT checkout_sessions_status_check
        CHECK (checkout_status IN ('PENDING', 'COMPLETED', 'EXPIRED', 'FAILED')),
    CONSTRAINT checkout_sessions_amount_check CHECK (amount_minor_units >= 0),
    CONSTRAINT checkout_sessions_currency_check CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT checkout_sessions_completion_check CHECK (
        (checkout_status = 'COMPLETED' AND completed_at IS NOT NULL)
        OR (checkout_status <> 'COMPLETED' AND completed_at IS NULL)
    ),
    CONSTRAINT checkout_sessions_provider_session_unique UNIQUE (provider_code, provider_session_id)
);

CREATE UNIQUE INDEX checkout_sessions_one_pending_per_user_idx
    ON checkout_sessions (user_id) WHERE checkout_status = 'PENDING';
CREATE INDEX checkout_sessions_user_created_idx
    ON checkout_sessions (user_id, created_at DESC);

CREATE TABLE billing_events (
    id UUID PRIMARY KEY,
    provider_code VARCHAR(32) NOT NULL,
    provider_event_id VARCHAR(255) NOT NULL,
    event_version INTEGER NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    processing_status VARCHAR(20) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    checkout_session_id UUID REFERENCES checkout_sessions(id) ON DELETE SET NULL,
    subscription_id UUID REFERENCES subscriptions(id) ON DELETE SET NULL,
    amount_minor_units BIGINT NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    CONSTRAINT billing_events_provider_event_unique UNIQUE (provider_code, provider_event_id),
    CONSTRAINT billing_events_version_check CHECK (event_version > 0),
    CONSTRAINT billing_events_type_check
        CHECK (event_type IN ('SUBSCRIPTION_CREATED', 'SUBSCRIPTION_RENEWED', 'PAYMENT_FAILED', 'SUBSCRIPTION_CANCELED', 'CHARGEBACK')),
    CONSTRAINT billing_events_status_check
        CHECK (processing_status IN ('PROCESSING', 'APPLIED', 'IGNORED_STALE')),
    CONSTRAINT billing_events_currency_check CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT billing_events_processed_check CHECK (
        (processing_status = 'PROCESSING' AND processed_at IS NULL)
        OR (processing_status <> 'PROCESSING' AND processed_at IS NOT NULL)
    )
);

CREATE INDEX billing_events_user_occurred_idx
    ON billing_events (user_id, occurred_at DESC);

CREATE TABLE billing_ledger_entries (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    subscription_id UUID NOT NULL REFERENCES subscriptions(id),
    billing_event_id UUID NOT NULL UNIQUE REFERENCES billing_events(id),
    entry_type VARCHAR(16) NOT NULL,
    amount_minor_units BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT billing_ledger_type_check CHECK (entry_type IN ('CHARGE', 'REFUND', 'CHARGEBACK')),
    CONSTRAINT billing_ledger_amount_check CHECK (
        (entry_type = 'CHARGE' AND amount_minor_units >= 0)
        OR (entry_type IN ('REFUND', 'CHARGEBACK') AND amount_minor_units <= 0)
    ),
    CONSTRAINT billing_ledger_currency_check CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX billing_ledger_user_created_idx
    ON billing_ledger_entries (user_id, created_at DESC);
