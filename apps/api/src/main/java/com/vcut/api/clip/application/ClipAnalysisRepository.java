package com.vcut.api.clip.application;

import com.vcut.api.clip.domain.AnalysisRunStatus;
import com.vcut.api.clip.domain.CandidateAction;
import com.vcut.api.clip.domain.CandidateStatus;
import com.vcut.api.clip.domain.ClipAnalysisRun;
import com.vcut.api.clip.domain.ClipCandidate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClipAnalysisRepository {

  int nextVersion(UUID videoId);

  ClipAnalysisRun saveRun(ClipAnalysisRun run);

  Optional<ClipAnalysisRun> findRunForUser(UUID videoId, UUID userId, int pipelineVersion);

  Optional<ClipAnalysisRun> findRun(UUID videoId, int pipelineVersion);

  Optional<ClipAnalysisRun> findRunById(UUID runId);

  Optional<ClipAnalysisRun> findLatestRunForUser(UUID videoId, UUID userId);

  List<ClipCandidate> findLatestCandidatesForUser(UUID videoId, UUID userId);

  Optional<ClipCandidate> findCandidateForUser(UUID candidateId, UUID userId);

  void saveCandidates(List<ClipCandidate> candidates);

  void updateRunStatus(
      UUID runId, AnalysisRunStatus status, String errorCode, String errorMessage, Instant now);

  void updateCandidateStatus(UUID candidateId, UUID userId, CandidateStatus status, Instant now);

  void recordAction(
      UUID candidateId, UUID videoId, UUID userId, CandidateAction action, Instant now);
}
