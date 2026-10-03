package com.vcut.api.clip.application;

import com.vcut.api.clip.domain.Clip;
import com.vcut.api.clip.domain.ClipVersion;
import java.util.Objects;

public record ClipAggregate(Clip clip, ClipVersion version) {

  public ClipAggregate {
    Objects.requireNonNull(clip, "clip");
    Objects.requireNonNull(version, "version");
    if (!clip.id().equals(version.clipId()) || clip.currentEditVersion() != version.editVersion()) {
      throw new IllegalArgumentException("clip aggregate must contain its current version");
    }
  }
}
