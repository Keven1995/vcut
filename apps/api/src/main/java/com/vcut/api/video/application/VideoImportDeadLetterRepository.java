package com.vcut.api.video.application;

import java.util.UUID;

public interface VideoImportDeadLetterRepository {

  void record(UUID importId, String providerId, String failureCode, int attempts);
}
