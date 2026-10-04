CREATE TABLE clip_publication_metadata (
    id UUID PRIMARY KEY,
    clip_id UUID NOT NULL REFERENCES clips(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    edit_version INTEGER NOT NULL,
    platform VARCHAR(16) NOT NULL,
    title VARCHAR(100) NOT NULL,
    description VARCHAR(2200) NOT NULL,
    hashtags_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    reviewed_at TIMESTAMPTZ,
    CONSTRAINT clip_publication_edit_version_check CHECK (edit_version > 0),
    CONSTRAINT clip_publication_platform_check CHECK (platform IN ('SHORTS', 'REELS', 'TIKTOK')),
    CONSTRAINT clip_publication_status_check CHECK (status IN ('DRAFT', 'REVIEWED')),
    CONSTRAINT clip_publication_reviewed_check CHECK (
        (status = 'DRAFT' AND reviewed_at IS NULL)
        OR (status = 'REVIEWED' AND reviewed_at IS NOT NULL)
    ),
    CONSTRAINT clip_publication_clip_version_platform_unique
        UNIQUE (clip_id, edit_version, platform)
);

CREATE INDEX clip_publication_user_updated_idx
    ON clip_publication_metadata (user_id, updated_at DESC);
