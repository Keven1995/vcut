CREATE TABLE video_scene_intervals (
    video_id UUID NOT NULL REFERENCES videos(id) ON DELETE CASCADE,
    pipeline_version INTEGER NOT NULL,
    scene_index INTEGER NOT NULL,
    start_seconds NUMERIC(12, 3) NOT NULL,
    end_seconds NUMERIC(12, 3) NOT NULL,
    confidence NUMERIC(5, 4) NOT NULL DEFAULT 1.0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (video_id, pipeline_version, scene_index),
    CONSTRAINT video_scene_intervals_version_check CHECK (pipeline_version > 0),
    CONSTRAINT video_scene_intervals_index_check CHECK (scene_index >= 0),
    CONSTRAINT video_scene_intervals_time_check CHECK (end_seconds > start_seconds),
    CONSTRAINT video_scene_intervals_confidence_check CHECK (confidence BETWEEN 0 AND 1)
);

CREATE INDEX video_scene_intervals_video_idx
    ON video_scene_intervals (video_id, pipeline_version, start_seconds);
