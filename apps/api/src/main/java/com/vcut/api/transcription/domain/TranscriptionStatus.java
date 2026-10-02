package com.vcut.api.transcription.domain;

public enum TranscriptionStatus {
  QUEUED,
  PROCESSING,
  RETRYING,
  COMPLETED,
  FAILED
}
