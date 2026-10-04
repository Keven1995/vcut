CREATE TABLE security_audit_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    route_template VARCHAR(255) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    http_status SMALLINT NOT NULL,
    correlation_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT security_audit_type_check CHECK (
        event_type IN (
            'AUTH_REGISTER', 'AUTH_LOGIN', 'AUTH_REFRESH', 'AUTH_LOGOUT', 'ACCOUNT_DELETE',
            'VIDEO_UPLOAD', 'VIDEO_IMPORT', 'IMPORT_CANCEL', 'VIDEO_DELETE', 'PROJECT_DELETE',
            'VIDEO_PROCESS', 'TRANSCRIPTION_REQUEST', 'CLIP_ANALYSIS_REQUEST',
            'CLIP_CREATE', 'CLIP_GENERATE', 'RENDER_REQUEST', 'RENDER_RETRY',
            'DOWNLOAD_URL', 'PUBLICATION_GENERATE', 'SUBSCRIPTION_CHECKOUT',
            'SUBSCRIPTION_CANCEL', 'PAYMENT_WEBHOOK'
        )
    ),
    CONSTRAINT security_audit_outcome_check CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILURE')),
    CONSTRAINT security_audit_status_check CHECK (http_status BETWEEN 100 AND 599),
    CONSTRAINT security_audit_expiration_check CHECK (expires_at > occurred_at)
);

CREATE INDEX security_audit_event_expiration_idx
    ON security_audit_events (expires_at, occurred_at);
CREATE INDEX security_audit_actor_created_idx
    ON security_audit_events (actor_user_id, occurred_at DESC);
