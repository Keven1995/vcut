CREATE TABLE pipelines (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    project_id UUID NOT NULL REFERENCES projects(id),
    video_id UUID NOT NULL REFERENCES videos(id),
    version INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pipelines_version_check CHECK (version > 0),
    CONSTRAINT pipelines_status_check CHECK (status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT pipelines_video_version_unique UNIQUE (video_id, version)
);

CREATE TABLE jobs (
    id UUID PRIMARY KEY,
    pipeline_id UUID NOT NULL REFERENCES pipelines(id),
    user_id UUID NOT NULL REFERENCES users(id),
    project_id UUID NOT NULL REFERENCES projects(id),
    video_id UUID NOT NULL REFERENCES videos(id),
    operation VARCHAR(96) NOT NULL,
    version INTEGER NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL,
    current_stage VARCHAR(64) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 1,
    progress NUMERIC(5, 2) NOT NULL DEFAULT 0,
    correlation_id UUID NOT NULL,
    error_code VARCHAR(96),
    error_message VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT jobs_version_check CHECK (version > 0),
    CONSTRAINT jobs_attempt_check CHECK (attempt > 0),
    CONSTRAINT jobs_progress_check CHECK (progress >= 0 AND progress <= 100),
    CONSTRAINT jobs_status_check CHECK (status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED'))
);

CREATE TABLE stage_runs (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES jobs(id),
    stage_name VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 1,
    progress NUMERIC(5, 2) NOT NULL DEFAULT 0,
    input_payload TEXT,
    output_payload TEXT,
    error_code VARCHAR(96),
    error_message VARCHAR(1000),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT stage_runs_attempt_check CHECK (attempt > 0),
    CONSTRAINT stage_runs_progress_check CHECK (progress >= 0 AND progress <= 100),
    CONSTRAINT stage_runs_status_check CHECK (status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'RETRYING', 'FAILED', 'DEAD_LETTER')),
    CONSTRAINT stage_runs_job_stage_unique UNIQUE (job_id, stage_name)
);

CREATE TABLE outbox_messages (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    routing_key VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT outbox_attempt_check CHECK (attempt >= 0)
);

CREATE INDEX pipelines_user_created_at_idx ON pipelines (user_id, created_at DESC);
CREATE INDEX pipelines_project_created_at_idx ON pipelines (project_id, created_at DESC);
CREATE INDEX jobs_user_created_at_idx ON jobs (user_id, created_at DESC);
CREATE INDEX jobs_video_created_at_idx ON jobs (video_id, created_at DESC);
CREATE INDEX jobs_status_updated_at_idx ON jobs (status, updated_at);
CREATE INDEX stage_runs_job_status_idx ON stage_runs (job_id, status);
CREATE INDEX outbox_pending_idx ON outbox_messages (available_at, created_at) WHERE published_at IS NULL;
