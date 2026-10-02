ALTER TABLE videos
    ADD CONSTRAINT videos_id_user_unique UNIQUE (id, user_id);

CREATE TABLE clip_analysis_runs (
    id UUID PRIMARY KEY,
    video_id UUID NOT NULL,
    user_id UUID NOT NULL,
    pipeline_version INTEGER NOT NULL,
    duration_preference VARCHAR(16) NOT NULL,
    custom_duration_seconds NUMERIC(12, 3),
    duration_seconds NUMERIC(12, 3),
    language VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(96),
    error_message VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT clip_analysis_runs_video_owner_fk
        FOREIGN KEY (video_id, user_id) REFERENCES videos (id, user_id),
    CONSTRAINT clip_analysis_runs_id_owner_unique UNIQUE (id, video_id, user_id),
    CONSTRAINT clip_analysis_runs_version_check CHECK (pipeline_version > 0),
    CONSTRAINT clip_analysis_runs_custom_duration_check CHECK (
        (duration_preference = 'CUSTOM' AND custom_duration_seconds > 0 AND custom_duration_seconds <= 90)
        OR (duration_preference <> 'CUSTOM' AND custom_duration_seconds IS NULL)
    ),
    CONSTRAINT clip_analysis_runs_duration_check CHECK (
        duration_seconds IS NULL OR (duration_seconds > 0 AND duration_seconds <= 90)
    ),
    CONSTRAINT clip_analysis_runs_preference_check CHECK (
        duration_preference IN ('AUTO', 'SHORT', 'MEDIUM', 'LONG', 'CUSTOM')
    ),
    CONSTRAINT clip_analysis_runs_status_check CHECK (
        status IN ('QUEUED', 'PROCESSING', 'RETRYING', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT clip_analysis_runs_video_version_unique UNIQUE (video_id, pipeline_version)
);

CREATE TABLE clip_candidates (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL,
    video_id UUID NOT NULL,
    user_id UUID NOT NULL,
    variant VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    start_seconds NUMERIC(12, 3) NOT NULL,
    end_seconds NUMERIC(12, 3) NOT NULL,
    group_key VARCHAR(128) NOT NULL,
    title VARCHAR(500) NOT NULL,
    description TEXT NOT NULL,
    justification TEXT NOT NULL,
    hook_score NUMERIC(8, 6) NOT NULL,
    context_score NUMERIC(8, 6) NOT NULL,
    development_score NUMERIC(8, 6) NOT NULL,
    payoff_score NUMERIC(8, 6) NOT NULL,
    independence_score NUMERIC(8, 6) NOT NULL,
    engagement_score NUMERIC(8, 6) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT clip_candidates_run_owner_fk
        FOREIGN KEY (analysis_run_id, video_id, user_id)
        REFERENCES clip_analysis_runs (id, video_id, user_id),
    CONSTRAINT clip_candidates_video_owner_fk
        FOREIGN KEY (video_id, user_id) REFERENCES videos (id, user_id),
    CONSTRAINT clip_candidates_id_owner_unique UNIQUE (id, video_id, user_id),
    CONSTRAINT clip_candidates_variant_check CHECK (variant IN ('SHORT', 'COMPLETE', 'CONTEXTUAL')),
    CONSTRAINT clip_candidates_status_check CHECK (
        status IN ('SUGGESTED', 'ACCEPTED', 'DISCARDED', 'SELECTED')
    ),
    CONSTRAINT clip_candidates_interval_check CHECK (
        start_seconds >= 0 AND end_seconds > start_seconds AND end_seconds - start_seconds <= 90
    ),
    CONSTRAINT clip_candidates_scores_check CHECK (
        hook_score >= 0 AND hook_score <= 1
        AND context_score >= 0 AND context_score <= 1
        AND development_score >= 0 AND development_score <= 1
        AND payoff_score >= 0 AND payoff_score <= 1
        AND independence_score >= 0 AND independence_score <= 1
        AND engagement_score >= 0 AND engagement_score <= 1
    )
);

CREATE TABLE clip_candidate_action_audit (
    id UUID PRIMARY KEY,
    candidate_id UUID NOT NULL,
    video_id UUID NOT NULL,
    user_id UUID NOT NULL,
    action VARCHAR(16) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT clip_candidate_audit_candidate_owner_fk
        FOREIGN KEY (candidate_id, video_id, user_id)
        REFERENCES clip_candidates (id, video_id, user_id),
    CONSTRAINT clip_candidate_audit_action_check CHECK (action IN ('ACCEPT', 'DISCARD', 'SELECT'))
);

CREATE TABLE clip_candidate_action_metrics (
    candidate_id UUID NOT NULL,
    video_id UUID NOT NULL,
    user_id UUID NOT NULL,
    accept_count INTEGER NOT NULL DEFAULT 0,
    discard_count INTEGER NOT NULL DEFAULT 0,
    select_count INTEGER NOT NULL DEFAULT 0,
    last_action_at TIMESTAMPTZ,
    PRIMARY KEY (candidate_id, user_id),
    CONSTRAINT clip_candidate_metrics_candidate_owner_fk
        FOREIGN KEY (candidate_id, video_id, user_id)
        REFERENCES clip_candidates (id, video_id, user_id),
    CONSTRAINT clip_candidate_metrics_counts_check CHECK (
        accept_count >= 0 AND discard_count >= 0 AND select_count >= 0
    )
);

CREATE INDEX clip_analysis_runs_user_video_idx
    ON clip_analysis_runs (user_id, video_id, pipeline_version DESC);
CREATE INDEX clip_candidates_run_order_idx
    ON clip_candidates (analysis_run_id, end_seconds, start_seconds);
CREATE INDEX clip_candidates_user_video_idx
    ON clip_candidates (user_id, video_id, created_at DESC);
CREATE INDEX clip_candidate_audit_candidate_idx
    ON clip_candidate_action_audit (candidate_id, occurred_at DESC);
