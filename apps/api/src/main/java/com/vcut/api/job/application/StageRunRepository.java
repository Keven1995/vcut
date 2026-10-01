package com.vcut.api.job.application;

import com.vcut.api.job.domain.StageRun;
import com.vcut.api.job.domain.StageRunStatus;
import java.time.Instant;
import java.util.UUID;

public interface StageRunRepository {

  StageRun save(StageRun stageRun);

  void updateProgress(
      UUID jobId,
      String stageName,
      StageRunStatus status,
      int attempt,
      double progress,
      Instant now);

  void complete(
      UUID jobId,
      String stageName,
      StageRunStatus status,
      String outputPayload,
      String errorCode,
      String errorMessage,
      Instant now);
}
