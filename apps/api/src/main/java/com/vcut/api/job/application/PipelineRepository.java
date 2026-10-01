package com.vcut.api.job.application;

import com.vcut.api.job.domain.Pipeline;
import com.vcut.api.job.domain.PipelineStatus;
import java.time.Instant;
import java.util.UUID;

public interface PipelineRepository {

  Pipeline save(Pipeline pipeline);

  void updateStatus(Pipeline pipeline);

  void updateStatus(UUID pipelineId, PipelineStatus status, Instant now);
}
