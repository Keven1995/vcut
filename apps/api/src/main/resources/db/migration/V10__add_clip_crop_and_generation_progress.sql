ALTER TABLE clips
    ADD COLUMN generation_progress INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT clips_generation_progress_check CHECK (generation_progress BETWEEN 0 AND 100);

ALTER TABLE clip_versions
    ADD COLUMN crop_x NUMERIC(5, 4) NOT NULL DEFAULT 0.5,
    ADD COLUMN crop_y NUMERIC(5, 4) NOT NULL DEFAULT 0.5,
    ADD COLUMN crop_zoom NUMERIC(5, 3) NOT NULL DEFAULT 1.0,
    ADD CONSTRAINT clip_versions_crop_x_check CHECK (crop_x BETWEEN 0 AND 1),
    ADD CONSTRAINT clip_versions_crop_y_check CHECK (crop_y BETWEEN 0 AND 1),
    ADD CONSTRAINT clip_versions_crop_zoom_check CHECK (crop_zoom BETWEEN 1 AND 3);
