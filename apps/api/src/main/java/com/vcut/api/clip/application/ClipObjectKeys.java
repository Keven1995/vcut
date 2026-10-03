package com.vcut.api.clip.application;

import java.util.UUID;

public final class ClipObjectKeys {

  private ClipObjectKeys() {}

  public static String preview(UUID userId, UUID projectId, UUID clipId, int editVersion) {
    return "users/"
        + userId
        + "/projects/"
        + projectId
        + "/clips/"
        + clipId
        + "/v"
        + editVersion
        + "/preview.mp4";
  }
}
