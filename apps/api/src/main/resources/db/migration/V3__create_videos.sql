CREATE TABLE videos (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    project_id UUID NOT NULL REFERENCES projects(id),
    object_key VARCHAR(512) NOT NULL UNIQUE,
    original_filename VARCHAR(255) NOT NULL,
    declared_content_type VARCHAR(127) NOT NULL,
    declared_size_bytes BIGINT NOT NULL,
    actual_size_bytes BIGINT,
    checksum_sha256 VARCHAR(64),
    duration_seconds NUMERIC(12, 3),
    width INTEGER,
    height INTEGER,
    frame_rate NUMERIC(10, 6),
    has_audio BOOLEAN,
    upload_status VARCHAR(32) NOT NULL,
    failure_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT videos_declared_size_check CHECK (declared_size_bytes > 0),
    CONSTRAINT videos_actual_size_check CHECK (actual_size_bytes IS NULL OR actual_size_bytes > 0),
    CONSTRAINT videos_duration_check CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
    CONSTRAINT videos_dimensions_check CHECK (
        (width IS NULL AND height IS NULL) OR (width > 0 AND height > 0)
    ),
    CONSTRAINT videos_frame_rate_check CHECK (frame_rate IS NULL OR frame_rate >= 0),
    CONSTRAINT videos_status_check CHECK (
        upload_status IN ('UPLOADING', 'UPLOADED', 'VALIDATING', 'READY', 'REJECTED', 'FAILED')
    )
);

CREATE INDEX videos_user_created_at_idx ON videos (user_id, created_at DESC);
CREATE INDEX videos_project_created_at_idx ON videos (project_id, created_at DESC);
