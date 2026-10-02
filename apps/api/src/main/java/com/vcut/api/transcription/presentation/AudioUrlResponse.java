package com.vcut.api.transcription.presentation;

import com.vcut.api.video.application.ObjectStorage;
import java.time.Instant;

public record AudioUrlResponse(String url, Instant expiresAt) {

  public static AudioUrlResponse from(ObjectStorage.PresignedDownload download) {
    return new AudioUrlResponse(download.url(), download.expiresAt());
  }
}
