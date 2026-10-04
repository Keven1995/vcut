CREATE TABLE video_import_provenance (
    video_id UUID PRIMARY KEY REFERENCES videos(id) ON DELETE CASCADE,
    provider_id VARCHAR(64) NOT NULL,
    source_origin VARCHAR(512) NOT NULL,
    external_asset_id VARCHAR(255),
    consent_policy_version VARCHAR(64) NOT NULL,
    rights_confirmed BOOLEAN NOT NULL,
    consent_accepted_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT video_import_provider_id_check CHECK (provider_id ~ '^[a-z0-9][a-z0-9-]{0,63}$'),
    CONSTRAINT video_import_consent_policy_check CHECK (length(consent_policy_version) > 0),
    CONSTRAINT video_import_rights_confirmed_check CHECK (rights_confirmed)
);
