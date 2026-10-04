ALTER TABLE users DROP CONSTRAINT users_status_check;
ALTER TABLE users
    ADD CONSTRAINT users_status_check
        CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED', 'DELETION_PENDING'));

ALTER TABLE refresh_sessions DROP CONSTRAINT refresh_sessions_user_id_fkey;
ALTER TABLE refresh_sessions
    ADD CONSTRAINT refresh_sessions_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE refresh_sessions DROP CONSTRAINT refresh_sessions_replaced_by_session_id_fkey;
ALTER TABLE refresh_sessions
    ADD CONSTRAINT refresh_sessions_replaced_by_session_id_fkey
        FOREIGN KEY (replaced_by_session_id) REFERENCES refresh_sessions(id) ON DELETE SET NULL;

ALTER TABLE projects DROP CONSTRAINT projects_user_id_fkey;
ALTER TABLE projects
    ADD CONSTRAINT projects_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE videos DROP CONSTRAINT videos_user_id_fkey;
ALTER TABLE videos DROP CONSTRAINT videos_project_id_fkey;
ALTER TABLE videos
    ADD CONSTRAINT videos_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    ADD CONSTRAINT videos_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

ALTER TABLE pipelines DROP CONSTRAINT pipelines_user_id_fkey;
ALTER TABLE pipelines DROP CONSTRAINT pipelines_project_id_fkey;
ALTER TABLE pipelines DROP CONSTRAINT pipelines_video_id_fkey;
ALTER TABLE pipelines
    ADD CONSTRAINT pipelines_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    ADD CONSTRAINT pipelines_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    ADD CONSTRAINT pipelines_video_id_fkey FOREIGN KEY (video_id) REFERENCES videos(id) ON DELETE CASCADE;

ALTER TABLE jobs DROP CONSTRAINT jobs_pipeline_id_fkey;
ALTER TABLE jobs DROP CONSTRAINT jobs_user_id_fkey;
ALTER TABLE jobs DROP CONSTRAINT jobs_project_id_fkey;
ALTER TABLE jobs DROP CONSTRAINT jobs_video_id_fkey;
ALTER TABLE jobs
    ADD CONSTRAINT jobs_pipeline_id_fkey FOREIGN KEY (pipeline_id) REFERENCES pipelines(id) ON DELETE CASCADE,
    ADD CONSTRAINT jobs_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    ADD CONSTRAINT jobs_project_id_fkey FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    ADD CONSTRAINT jobs_video_id_fkey FOREIGN KEY (video_id) REFERENCES videos(id) ON DELETE CASCADE;

ALTER TABLE stage_runs DROP CONSTRAINT stage_runs_job_id_fkey;
ALTER TABLE stage_runs
    ADD CONSTRAINT stage_runs_job_id_fkey FOREIGN KEY (job_id) REFERENCES jobs(id) ON DELETE CASCADE;

ALTER TABLE transcriptions DROP CONSTRAINT transcriptions_video_id_fkey;
ALTER TABLE transcriptions DROP CONSTRAINT transcriptions_user_id_fkey;
ALTER TABLE transcriptions
    ADD CONSTRAINT transcriptions_video_id_fkey FOREIGN KEY (video_id) REFERENCES videos(id) ON DELETE CASCADE,
    ADD CONSTRAINT transcriptions_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE clip_analysis_runs DROP CONSTRAINT clip_analysis_runs_video_owner_fk;
ALTER TABLE clip_analysis_runs
    ADD CONSTRAINT clip_analysis_runs_video_owner_fk
        FOREIGN KEY (video_id, user_id) REFERENCES videos(id, user_id) ON DELETE CASCADE;

ALTER TABLE clip_candidates DROP CONSTRAINT clip_candidates_run_owner_fk;
ALTER TABLE clip_candidates DROP CONSTRAINT clip_candidates_video_owner_fk;
ALTER TABLE clip_candidates
    ADD CONSTRAINT clip_candidates_run_owner_fk
        FOREIGN KEY (analysis_run_id, video_id, user_id)
        REFERENCES clip_analysis_runs(id, video_id, user_id) ON DELETE CASCADE,
    ADD CONSTRAINT clip_candidates_video_owner_fk
        FOREIGN KEY (video_id, user_id) REFERENCES videos(id, user_id) ON DELETE CASCADE;

ALTER TABLE clip_candidate_action_audit DROP CONSTRAINT clip_candidate_audit_candidate_owner_fk;
ALTER TABLE clip_candidate_action_audit
    ADD CONSTRAINT clip_candidate_audit_candidate_owner_fk
        FOREIGN KEY (candidate_id, video_id, user_id)
        REFERENCES clip_candidates(id, video_id, user_id) ON DELETE CASCADE;
ALTER TABLE clip_candidate_action_metrics DROP CONSTRAINT clip_candidate_metrics_candidate_owner_fk;
ALTER TABLE clip_candidate_action_metrics
    ADD CONSTRAINT clip_candidate_metrics_candidate_owner_fk
        FOREIGN KEY (candidate_id, video_id, user_id)
        REFERENCES clip_candidates(id, video_id, user_id) ON DELETE CASCADE;

ALTER TABLE clips DROP CONSTRAINT clips_user_fk;
ALTER TABLE clips DROP CONSTRAINT clips_project_owner_fk;
ALTER TABLE clips DROP CONSTRAINT clips_video_owner_fk;
ALTER TABLE clips DROP CONSTRAINT clips_candidate_owner_fk;
ALTER TABLE clips
    ADD CONSTRAINT clips_user_fk FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    ADD CONSTRAINT clips_project_owner_fk
        FOREIGN KEY (project_id, user_id) REFERENCES projects(id, user_id) ON DELETE CASCADE,
    ADD CONSTRAINT clips_video_owner_fk
        FOREIGN KEY (video_id, user_id) REFERENCES videos(id, user_id) ON DELETE CASCADE,
    ADD CONSTRAINT clips_candidate_owner_fk
        FOREIGN KEY (candidate_id, video_id, user_id)
        REFERENCES clip_candidates(id, video_id, user_id) ON DELETE CASCADE;

ALTER TABLE clip_versions DROP CONSTRAINT clip_versions_clip_owner_fk;
ALTER TABLE clip_versions DROP CONSTRAINT clip_versions_project_owner_fk;
ALTER TABLE clip_versions DROP CONSTRAINT clip_versions_video_owner_fk;
ALTER TABLE clip_versions DROP CONSTRAINT clip_versions_candidate_owner_fk;
ALTER TABLE clip_versions
    ADD CONSTRAINT clip_versions_clip_owner_fk
        FOREIGN KEY (clip_id, user_id) REFERENCES clips(id, user_id) ON DELETE CASCADE,
    ADD CONSTRAINT clip_versions_project_owner_fk
        FOREIGN KEY (project_id, user_id) REFERENCES projects(id, user_id) ON DELETE CASCADE,
    ADD CONSTRAINT clip_versions_video_owner_fk
        FOREIGN KEY (video_id, user_id) REFERENCES videos(id, user_id) ON DELETE CASCADE,
    ADD CONSTRAINT clip_versions_candidate_owner_fk
        FOREIGN KEY (candidate_id, video_id, user_id)
        REFERENCES clip_candidates(id, video_id, user_id) ON DELETE CASCADE;

ALTER TABLE clip_renders DROP CONSTRAINT clip_renders_clip_owner_fk;
ALTER TABLE clip_renders DROP CONSTRAINT clip_renders_project_owner_fk;
ALTER TABLE clip_renders
    ADD CONSTRAINT clip_renders_clip_owner_fk
        FOREIGN KEY (clip_id, user_id) REFERENCES clips(id, user_id) ON DELETE CASCADE,
    ADD CONSTRAINT clip_renders_project_owner_fk
        FOREIGN KEY (project_id, user_id) REFERENCES projects(id, user_id) ON DELETE CASCADE;

ALTER TABLE subscriptions ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE subscriptions DROP CONSTRAINT subscriptions_user_id_fkey;
ALTER TABLE subscriptions
    ADD CONSTRAINT subscriptions_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE billing_ledger_entries ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE billing_ledger_entries DROP CONSTRAINT billing_ledger_entries_user_id_fkey;
ALTER TABLE billing_ledger_entries
    ADD CONSTRAINT billing_ledger_entries_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;

CREATE TABLE account_deletion_requests (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    delete_after TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    claimed_at TIMESTAMPTZ,
    last_failure_code VARCHAR(64),
    completed_at TIMESTAMPTZ,
    CONSTRAINT account_deletion_status_check
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT account_deletion_attempt_check CHECK (attempt_count >= 0),
    CONSTRAINT account_deletion_period_check CHECK (delete_after > requested_at),
    CONSTRAINT account_deletion_completion_check CHECK (
        (status IN ('COMPLETED', 'CANCELLED') AND completed_at IS NOT NULL)
        OR (status IN ('PENDING', 'PROCESSING') AND completed_at IS NULL)
    ),
    CONSTRAINT account_deletion_failure_code_check CHECK (
        last_failure_code IS NULL OR last_failure_code ~ '^[A-Z0-9_]{1,64}$'
    )
);

CREATE UNIQUE INDEX account_deletion_active_user_idx
    ON account_deletion_requests (user_id)
    WHERE user_id IS NOT NULL AND status IN ('PENDING', 'PROCESSING');
CREATE INDEX account_deletion_due_idx
    ON account_deletion_requests (delete_after, requested_at)
    WHERE status = 'PENDING';
