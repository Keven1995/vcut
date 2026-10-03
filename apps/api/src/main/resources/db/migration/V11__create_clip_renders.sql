CREATE TABLE clip_renders (
    id UUID PRIMARY KEY,
    clip_id UUID NOT NULL,
    user_id UUID NOT NULL,
    project_id UUID NOT NULL,
    edit_version INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    progress INTEGER NOT NULL DEFAULT 0,
    output_object_key VARCHAR(512),
    thumbnail_object_key VARCHAR(512),
    output_width INTEGER,
    output_height INTEGER,
    output_duration_seconds NUMERIC(12, 3),
    output_aspect_ratio VARCHAR(5),
    error_code VARCHAR(128),
    error_message VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT clip_renders_clip_owner_fk FOREIGN KEY (clip_id, user_id)
        REFERENCES clips (id, user_id),
    CONSTRAINT clip_renders_project_owner_fk FOREIGN KEY (project_id, user_id)
        REFERENCES projects (id, user_id),
    CONSTRAINT clip_renders_edit_version_check CHECK (edit_version > 0),
    CONSTRAINT clip_renders_status_check CHECK (status IN ('QUEUED', 'PROCESSING', 'READY', 'FAILED')),
    CONSTRAINT clip_renders_progress_check CHECK (progress BETWEEN 0 AND 100),
    CONSTRAINT clip_renders_output_dimensions_check CHECK (
        (output_width IS NULL AND output_height IS NULL)
        OR (output_width > 0 AND output_height > 0)
    ),
    CONSTRAINT clip_renders_output_ratio_check CHECK (
        output_aspect_ratio IS NULL OR output_aspect_ratio IN ('9:16', '16:9')
    ),
    CONSTRAINT clip_renders_ready_output_check CHECK (
        status <> 'READY'
        OR (
            output_object_key IS NOT NULL
            AND thumbnail_object_key IS NOT NULL
            AND output_width IS NOT NULL
            AND output_height IS NOT NULL
            AND output_duration_seconds IS NOT NULL
            AND output_aspect_ratio IS NOT NULL
        )
    ),
    CONSTRAINT clip_renders_one_version UNIQUE (clip_id, edit_version)
);

CREATE INDEX clip_renders_user_clip_created_idx
    ON clip_renders (user_id, clip_id, created_at DESC);
CREATE INDEX clip_renders_user_status_idx
    ON clip_renders (user_id, status, updated_at DESC);
