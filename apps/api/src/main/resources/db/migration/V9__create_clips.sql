ALTER TABLE projects
    ADD CONSTRAINT projects_id_user_unique UNIQUE (id, user_id);

CREATE TABLE clips (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    project_id UUID NOT NULL,
    video_id UUID NOT NULL,
    candidate_id UUID NOT NULL,
    hook_score NUMERIC(8, 6) NOT NULL,
    context_score NUMERIC(8, 6) NOT NULL,
    development_score NUMERIC(8, 6) NOT NULL,
    payoff_score NUMERIC(8, 6) NOT NULL,
    independence_score NUMERIC(8, 6) NOT NULL,
    engagement_score NUMERIC(8, 6) NOT NULL,
    current_edit_version INTEGER NOT NULL,
    generation_requested_version INTEGER,
    status VARCHAR(16) NOT NULL,
    output_object_key VARCHAR(512),
    output_width INTEGER,
    output_height INTEGER,
    output_duration_seconds NUMERIC(12, 3),
    output_aspect_ratio VARCHAR(5),
    output_edit_version INTEGER,
    error_code VARCHAR(96),
    error_message VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT clips_user_fk FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT clips_project_owner_fk FOREIGN KEY (project_id, user_id)
        REFERENCES projects (id, user_id),
    CONSTRAINT clips_video_owner_fk FOREIGN KEY (video_id, user_id)
        REFERENCES videos (id, user_id),
    CONSTRAINT clips_candidate_owner_fk FOREIGN KEY (candidate_id, video_id, user_id)
        REFERENCES clip_candidates (id, video_id, user_id),
    CONSTRAINT clips_id_user_unique UNIQUE (id, user_id),
    CONSTRAINT clips_edit_version_check CHECK (current_edit_version > 0),
    CONSTRAINT clips_generation_version_check CHECK (
        generation_requested_version IS NULL OR generation_requested_version > 0
    ),
    CONSTRAINT clips_status_check CHECK (status IN ('QUEUED', 'PROCESSING', 'READY', 'FAILED')),
    CONSTRAINT clips_score_check CHECK (
        hook_score >= 0 AND hook_score <= 1
        AND context_score >= 0 AND context_score <= 1
        AND development_score >= 0 AND development_score <= 1
        AND payoff_score >= 0 AND payoff_score <= 1
        AND independence_score >= 0 AND independence_score <= 1
        AND engagement_score >= 0 AND engagement_score <= 1
    ),
    CONSTRAINT clips_output_dimensions_check CHECK (
        (output_width IS NULL AND output_height IS NULL)
        OR (output_width > 0 AND output_height > 0)
    ),
    CONSTRAINT clips_output_duration_check CHECK (
        output_duration_seconds IS NULL OR output_duration_seconds > 0
    ),
    CONSTRAINT clips_output_ratio_check CHECK (
        output_aspect_ratio IS NULL OR output_aspect_ratio IN ('9:16', '16:9')
    ),
    CONSTRAINT clips_output_version_check CHECK (
        output_edit_version IS NULL OR output_edit_version > 0
    ),
    CONSTRAINT clips_ready_output_check CHECK (
        status <> 'READY'
        OR (
            output_object_key IS NOT NULL
            AND output_width IS NOT NULL
            AND output_height IS NOT NULL
            AND output_duration_seconds IS NOT NULL
            AND output_aspect_ratio IS NOT NULL
            AND output_edit_version = current_edit_version
        )
    )
);

CREATE TABLE clip_versions (
    id UUID PRIMARY KEY,
    clip_id UUID NOT NULL,
    user_id UUID NOT NULL,
    project_id UUID NOT NULL,
    video_id UUID NOT NULL,
    candidate_id UUID NOT NULL,
    edit_version INTEGER NOT NULL,
    start_seconds NUMERIC(12, 3) NOT NULL,
    end_seconds NUMERIC(12, 3) NOT NULL,
    aspect_ratio VARCHAR(5) NOT NULL,
    caption_preset VARCHAR(16) NOT NULL,
    font_family VARCHAR(128) NOT NULL,
    font_size INTEGER NOT NULL,
    font_weight INTEGER NOT NULL,
    text_color VARCHAR(7) NOT NULL,
    background_color VARCHAR(7) NOT NULL,
    background_opacity NUMERIC(5, 4) NOT NULL,
    caption_position VARCHAR(16) NOT NULL,
    caption_animation VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT clip_versions_clip_owner_fk FOREIGN KEY (clip_id, user_id)
        REFERENCES clips (id, user_id),
    CONSTRAINT clip_versions_project_owner_fk FOREIGN KEY (project_id, user_id)
        REFERENCES projects (id, user_id),
    CONSTRAINT clip_versions_video_owner_fk FOREIGN KEY (video_id, user_id)
        REFERENCES videos (id, user_id),
    CONSTRAINT clip_versions_candidate_owner_fk FOREIGN KEY (candidate_id, video_id, user_id)
        REFERENCES clip_candidates (id, video_id, user_id),
    CONSTRAINT clip_versions_edit_version_check CHECK (edit_version > 0),
    CONSTRAINT clip_versions_interval_check CHECK (
        start_seconds >= 0
        AND end_seconds > start_seconds
        AND end_seconds - start_seconds <= 90
    ),
    CONSTRAINT clip_versions_ratio_check CHECK (aspect_ratio IN ('9:16', '16:9')),
    CONSTRAINT clip_versions_preset_check CHECK (
        caption_preset IN ('MINIMAL', 'BOLD', 'KARAOKE', 'PODCAST', 'GAMING', 'TIKTOK')
    ),
    CONSTRAINT clip_versions_font_size_check CHECK (font_size BETWEEN 8 AND 144),
    CONSTRAINT clip_versions_font_weight_check CHECK (
        font_weight BETWEEN 100 AND 900 AND MOD(font_weight, 100) = 0
    ),
    CONSTRAINT clip_versions_text_color_check CHECK (text_color ~ '^#[0-9A-Fa-f]{6}$'),
    CONSTRAINT clip_versions_background_color_check CHECK (
        background_color ~ '^#[0-9A-Fa-f]{6}$'
    ),
    CONSTRAINT clip_versions_background_opacity_check CHECK (
        background_opacity >= 0 AND background_opacity <= 1
    ),
    CONSTRAINT clip_versions_position_check CHECK (
        caption_position IN ('TOP', 'CENTER', 'BOTTOM')
    ),
    CONSTRAINT clip_versions_animation_check CHECK (
        caption_animation IN ('NONE', 'FADE', 'POP', 'KARAOKE')
    ),
    CONSTRAINT clip_versions_unique_version UNIQUE (clip_id, edit_version)
);

CREATE TABLE caption_cues (
    id UUID PRIMARY KEY,
    clip_version_id UUID NOT NULL REFERENCES clip_versions(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    cue_text TEXT NOT NULL,
    start_seconds NUMERIC(12, 3) NOT NULL,
    end_seconds NUMERIC(12, 3) NOT NULL,
    CONSTRAINT caption_cues_position_check CHECK (position >= 0),
    CONSTRAINT caption_cues_text_check CHECK (char_length(cue_text) BETWEEN 1 AND 1000),
    CONSTRAINT caption_cues_interval_check CHECK (
        start_seconds >= 0 AND end_seconds > start_seconds
    ),
    CONSTRAINT caption_cues_unique_position UNIQUE (clip_version_id, position)
);

CREATE INDEX clips_user_video_status_idx
    ON clips (user_id, video_id, status, created_at DESC);
CREATE INDEX clips_user_created_at_idx
    ON clips (user_id, created_at DESC);
CREATE INDEX clip_versions_clip_version_idx
    ON clip_versions (clip_id, edit_version DESC);
CREATE INDEX caption_cues_version_position_idx
    ON caption_cues (clip_version_id, position);
