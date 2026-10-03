package com.vcut.api.clip.presentation;

import com.vcut.api.clip.domain.CaptionCue;
import java.math.BigDecimal;
import java.util.UUID;

public record CaptionCueResponse(
    UUID id, String text, BigDecimal startSeconds, BigDecimal endSeconds) {

  public static CaptionCueResponse from(CaptionCue cue) {
    return new CaptionCueResponse(cue.id(), cue.text(), cue.startSeconds(), cue.endSeconds());
  }
}
