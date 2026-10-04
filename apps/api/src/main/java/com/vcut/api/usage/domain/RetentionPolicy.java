package com.vcut.api.usage.domain;

import java.time.Duration;

public record RetentionPolicy(
    long originalMediaDays,
    long audioDays,
    long framesDays,
    long previewDays,
    long finalDays,
    long thumbnailDays,
    long failedJobArtifactDays) {

  public RetentionPolicy {
    if (originalMediaDays < 0
        || audioDays < 0
        || framesDays < 0
        || previewDays < 0
        || finalDays < 0
        || thumbnailDays < 0
        || failedJobArtifactDays < 0) {
      throw new IllegalArgumentException("retention durations must not be negative");
    }
  }

  public Duration retentionFor(RetentionAssetType assetType) {
    long days =
        switch (assetType) {
          case ORIGINAL -> originalMediaDays;
          case NORMALIZED -> originalMediaDays;
          case AUDIO -> audioDays;
          case FRAMES -> framesDays;
          case PREVIEW -> previewDays;
          case FINAL -> finalDays;
          case THUMBNAIL -> thumbnailDays;
          case FAILED_JOB_ARTIFACT -> failedJobArtifactDays;
        };
    return Duration.ofDays(days);
  }
}
