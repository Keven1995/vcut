CREATE INDEX clips_user_active_idx
    ON clips (user_id)
    WHERE status IN ('QUEUED', 'PROCESSING');

CREATE INDEX clips_user_video_page_idx
    ON clips (user_id, video_id, created_at DESC, id)
    INCLUDE (current_edit_version);
