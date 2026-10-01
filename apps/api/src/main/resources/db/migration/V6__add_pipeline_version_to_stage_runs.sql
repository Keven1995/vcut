ALTER TABLE stage_runs
    ADD COLUMN pipeline_version INTEGER NOT NULL DEFAULT 1;

ALTER TABLE stage_runs
    ADD CONSTRAINT stage_runs_pipeline_version_check CHECK (pipeline_version > 0);

CREATE INDEX stage_runs_pipeline_version_idx
    ON stage_runs (job_id, pipeline_version, stage_name);
