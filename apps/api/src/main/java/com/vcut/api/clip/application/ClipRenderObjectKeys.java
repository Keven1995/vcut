package com.vcut.api.clip.application;

import java.util.UUID;

public final class ClipRenderObjectKeys {

  private ClipRenderObjectKeys() {}

  public static String finalVideo(UUID userId, UUID projectId, UUID clipId, int editVersion) {
    return base(userId, projectId, clipId, editVersion) + "/final.mp4";
  }

  public static String thumbnail(UUID userId, UUID projectId, UUID clipId, int editVersion) {
    return base(userId, projectId, clipId, editVersion) + "/thumbnail.jpg";
  }

  private static String base(UUID userId, UUID projectId, UUID clipId, int editVersion) {
    return "users/"
        + userId
        + "/projects/"
        + projectId
        + "/clips/"
        + clipId
        + "/v"
        + editVersion
        + "/final";
  }
}
