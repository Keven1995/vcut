package com.vcut.api.job.domain;

public enum StageRunStatus {
  QUEUED,
  PROCESSING,
  COMPLETED,
  RETRYING,
  FAILED,
  DEAD_LETTER
}
