package com.vcut.api.job.application;

import com.vcut.api.job.domain.Job;
import com.vcut.api.job.domain.JobStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository {

  Job save(Job job);

  Optional<Job> findByIdForUser(UUID jobId, UUID userId);

  Optional<Job> findById(UUID jobId);

  Optional<Job> findByIdempotencyKeyForUser(String idempotencyKey, UUID userId);

  void updateProgress(
      UUID jobId, JobStatus status, String stage, int attempt, double progress, Instant now);

  void complete(
      UUID jobId,
      JobStatus status,
      String stage,
      double progress,
      String errorCode,
      String errorMessage,
      Instant now);
}
