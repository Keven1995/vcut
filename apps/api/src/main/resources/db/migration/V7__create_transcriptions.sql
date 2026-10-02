CREATE TABLE transcriptions (
    id UUID PRIMARY KEY,
    video_id UUID NOT NULL REFERENCES videos(id),
    user_id UUID NOT NULL REFERENCES users(id),
    pipeline_version INTEGER NOT NULL,
    provider VARCHAR(128) NOT NULL,
    language VARCHAR(16) NOT NULL,
    transcript_text TEXT NOT NULL,
    duration_seconds NUMERIC(12, 3) NOT NULL DEFAULT 0,
    confidence NUMERIC(5, 4),
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(96),
    error_message VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT transcriptions_version_check CHECK (pipeline_version > 0),
    CONSTRAINT transcriptions_duration_check CHECK (duration_seconds >= 0),
    CONSTRAINT transcriptions_confidence_check CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    CONSTRAINT transcriptions_status_check CHECK (status IN ('QUEUED', 'PROCESSING', 'RETRYING', 'COMPLETED', 'FAILED')),
    CONSTRAINT transcriptions_video_version_unique UNIQUE (video_id, pipeline_version)
);

CREATE TABLE transcription_segments (
    id UUID PRIMARY KEY,
    transcription_id UUID NOT NULL REFERENCES transcriptions(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    segment_text TEXT NOT NULL,
    start_seconds NUMERIC(12, 3) NOT NULL,
    end_seconds NUMERIC(12, 3) NOT NULL,
    confidence NUMERIC(5, 4),
    CONSTRAINT transcription_segments_position_check CHECK (position >= 0),
    CONSTRAINT transcription_segments_range_check CHECK (end_seconds > start_seconds),
    CONSTRAINT transcription_segments_confidence_check CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    CONSTRAINT transcription_segments_unique_position UNIQUE (transcription_id, position)
);

CREATE TABLE transcription_words (
    id UUID PRIMARY KEY,
    segment_id UUID NOT NULL REFERENCES transcription_segments(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    word_text VARCHAR(1000) NOT NULL,
    start_seconds NUMERIC(12, 3) NOT NULL,
    end_seconds NUMERIC(12, 3) NOT NULL,
    confidence NUMERIC(5, 4),
    CONSTRAINT transcription_words_position_check CHECK (position >= 0),
    CONSTRAINT transcription_words_range_check CHECK (end_seconds > start_seconds),
    CONSTRAINT transcription_words_confidence_check CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    CONSTRAINT transcription_words_unique_position UNIQUE (segment_id, position)
);

CREATE INDEX transcriptions_video_status_idx ON transcriptions (video_id, status, pipeline_version DESC);
CREATE INDEX transcription_segments_order_idx ON transcription_segments (transcription_id, position);
CREATE INDEX transcription_words_order_idx ON transcription_words (segment_id, position);
