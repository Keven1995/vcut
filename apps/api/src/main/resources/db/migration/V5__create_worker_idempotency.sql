CREATE TABLE worker_idempotency (
    idempotency_key VARCHAR(512) PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    result_payload TEXT,
    claimed_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT worker_idempotency_status_check CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);

CREATE INDEX worker_idempotency_claimed_at_idx ON worker_idempotency (status, claimed_at);
