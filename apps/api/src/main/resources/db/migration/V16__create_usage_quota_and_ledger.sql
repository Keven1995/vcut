CREATE TABLE subscriptions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    plan_code VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT subscriptions_plan_check CHECK (plan_code IN ('FREE', 'PRO')),
    CONSTRAINT subscriptions_status_check CHECK (status IN ('ACTIVE', 'CANCELED', 'EXPIRED')),
    CONSTRAINT subscriptions_period_check CHECK (period_end > period_start)
);

CREATE UNIQUE INDEX subscriptions_one_active_per_user_idx
    ON subscriptions (user_id) WHERE status = 'ACTIVE';

CREATE TABLE usage_periods (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    plan_code VARCHAR(16) NOT NULL,
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    reserved_minutes NUMERIC(12, 3) NOT NULL DEFAULT 0,
    processed_minutes NUMERIC(12, 3) NOT NULL DEFAULT 0,
    stored_bytes BIGINT NOT NULL DEFAULT 0,
    renders BIGINT NOT NULL DEFAULT 0,
    transcription_minutes NUMERIC(12, 3) NOT NULL DEFAULT 0,
    multimodal_minutes NUMERIC(12, 3) NOT NULL DEFAULT 0,
    llm_tokens BIGINT NOT NULL DEFAULT 0,
    cpu_seconds NUMERIC(16, 3) NOT NULL DEFAULT 0,
    gpu_seconds NUMERIC(16, 3) NOT NULL DEFAULT 0,
    bandwidth_bytes BIGINT NOT NULL DEFAULT 0,
    estimated_cost NUMERIC(16, 6) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT usage_periods_plan_check CHECK (plan_code IN ('FREE', 'PRO')),
    CONSTRAINT usage_periods_range_check CHECK (period_end > period_start),
    CONSTRAINT usage_periods_nonnegative_check CHECK (
        reserved_minutes >= 0 AND processed_minutes >= 0 AND stored_bytes >= 0
        AND renders >= 0 AND transcription_minutes >= 0 AND multimodal_minutes >= 0 AND llm_tokens >= 0
        AND cpu_seconds >= 0 AND gpu_seconds >= 0 AND bandwidth_bytes >= 0
        AND estimated_cost >= 0
    ),
    CONSTRAINT usage_periods_user_period_unique UNIQUE (user_id, period_start)
);

CREATE INDEX usage_periods_user_idx ON usage_periods (user_id, period_start DESC);

CREATE TABLE quota_reservations (
    id UUID PRIMARY KEY,
    usage_period_id UUID NOT NULL REFERENCES usage_periods(id),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    resource_id UUID NOT NULL,
    operation VARCHAR(96) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    reserved_minutes NUMERIC(12, 3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT quota_reservation_minutes_check CHECK (reserved_minutes > 0),
    CONSTRAINT quota_reservation_status_check CHECK (status IN ('RESERVED', 'CONFIRMED', 'RELEASED'))
);

CREATE INDEX quota_reservations_period_status_idx
    ON quota_reservations (usage_period_id, status);

CREATE TABLE usage_ledger_entries (
    id UUID PRIMARY KEY,
    usage_period_id UUID NOT NULL REFERENCES usage_periods(id),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    resource_id UUID NOT NULL,
    operation VARCHAR(96) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    reserved_minutes NUMERIC(12, 3) NOT NULL DEFAULT 0,
    processed_minutes NUMERIC(12, 3) NOT NULL DEFAULT 0,
    storage_bytes BIGINT NOT NULL DEFAULT 0,
    renders BIGINT NOT NULL DEFAULT 0,
    transcription_minutes NUMERIC(12, 3) NOT NULL DEFAULT 0,
    multimodal_minutes NUMERIC(12, 3) NOT NULL DEFAULT 0,
    llm_tokens BIGINT NOT NULL DEFAULT 0,
    cpu_seconds NUMERIC(16, 3) NOT NULL DEFAULT 0,
    gpu_seconds NUMERIC(16, 3) NOT NULL DEFAULT 0,
    bandwidth_bytes BIGINT NOT NULL DEFAULT 0,
    estimated_cost NUMERIC(16, 6) NOT NULL DEFAULT 0,
    outcome VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT usage_ledger_outcome_check CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'RELEASED')),
    CONSTRAINT usage_ledger_nonnegative_check CHECK (
        reserved_minutes >= 0 AND processed_minutes >= 0 AND storage_bytes >= 0
        AND renders >= 0 AND transcription_minutes >= 0 AND multimodal_minutes >= 0 AND llm_tokens >= 0
        AND cpu_seconds >= 0 AND gpu_seconds >= 0 AND bandwidth_bytes >= 0
        AND estimated_cost >= 0
    )
);

CREATE INDEX usage_ledger_user_created_idx
    ON usage_ledger_entries (user_id, created_at DESC);

CREATE TABLE retained_objects (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    project_id UUID NOT NULL,
    object_key VARCHAR(512) NOT NULL UNIQUE,
    asset_type VARCHAR(32) NOT NULL,
    size_bytes BIGINT NOT NULL,
    retention_status VARCHAR(20) NOT NULL DEFAULT 'RETAINED',
    expires_at TIMESTAMPTZ NOT NULL,
    delete_attempts INTEGER NOT NULL DEFAULT 0,
    last_failure_code VARCHAR(64),
    claimed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT retained_objects_type_check CHECK (
        asset_type IN ('ORIGINAL', 'NORMALIZED', 'AUDIO', 'FRAMES', 'PREVIEW', 'FINAL', 'THUMBNAIL', 'FAILED_JOB_ARTIFACT')
    ),
    CONSTRAINT retained_objects_status_check CHECK (
        retention_status IN ('RETAINED', 'DELETE_PENDING', 'DELETED')
    ),
    CONSTRAINT retained_objects_size_check CHECK (size_bytes >= 0),
    CONSTRAINT retained_objects_attempt_check CHECK (delete_attempts >= 0)
);

CREATE INDEX retained_objects_expiration_idx
    ON retained_objects (expires_at, created_at)
    WHERE retention_status IN ('RETAINED', 'DELETE_PENDING');
CREATE INDEX retained_objects_user_status_idx
    ON retained_objects (user_id, retention_status);
