package com.vcut.api.transcription.application;

import com.vcut.api.transcription.domain.Transcription;
import com.vcut.api.transcription.domain.TranscriptionStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TranscriptionRepository {

  int nextVersion(UUID videoId);

  Transcription save(Transcription transcription);

  Optional<Transcription> findLatestForUser(UUID videoId, UUID userId);

  Optional<Transcription> findByVideoAndVersion(UUID videoId, int pipelineVersion);

  void updateStatus(
      UUID videoId,
      int pipelineVersion,
      TranscriptionStatus status,
      String errorCode,
      String errorMessage,
      Instant now);

  void complete(Transcription transcription);
}
