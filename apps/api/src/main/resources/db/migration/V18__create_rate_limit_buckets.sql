CREATE TABLE rate_limit_buckets (
    subject_scope VARCHAR(8) NOT NULL,
    subject_hash VARCHAR(64) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    window_started_at TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL DEFAULT 0,
    violation_count INTEGER NOT NULL DEFAULT 0,
    blocked_until TIMESTAMPTZ,
    last_violation_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (subject_scope, subject_hash, operation),
    CONSTRAINT rate_limit_scope_check CHECK (subject_scope IN ('IP', 'USER')),
    CONSTRAINT rate_limit_hash_check CHECK (subject_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT rate_limit_operation_check CHECK (length(operation) BETWEEN 1 AND 64),
    CONSTRAINT rate_limit_counts_check CHECK (request_count >= 0 AND violation_count >= 0)
);

CREATE INDEX rate_limit_updated_idx ON rate_limit_buckets (updated_at);
