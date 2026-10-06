CREATE INDEX transcriptions_user_active_idx
    ON transcriptions (user_id)
    WHERE status IN ('QUEUED', 'PROCESSING', 'RETRYING');

CREATE INDEX clip_analysis_runs_user_active_idx
    ON clip_analysis_runs (user_id)
    WHERE status IN ('QUEUED', 'PROCESSING', 'RETRYING');
