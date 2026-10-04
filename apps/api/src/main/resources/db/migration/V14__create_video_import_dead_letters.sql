CREATE TABLE video_import_dead_letters (
    import_id UUID PRIMARY KEY,
    provider_id VARCHAR(64) NOT NULL,
    failure_code VARCHAR(64) NOT NULL,
    attempts INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT video_import_dead_letter_provider_check CHECK (provider_id ~ '^[a-z0-9][a-z0-9-]{0,63}$'),
    CONSTRAINT video_import_dead_letter_failure_check CHECK (failure_code ~ '^[A-Z0-9_]{1,64}$'),
    CONSTRAINT video_import_dead_letter_attempts_check CHECK (attempts > 0)
);
