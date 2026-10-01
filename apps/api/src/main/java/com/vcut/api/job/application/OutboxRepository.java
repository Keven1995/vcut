package com.vcut.api.job.application;

import com.vcut.api.job.domain.OutboxMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository {

  OutboxMessage save(OutboxMessage message);

  List<OutboxMessage> findPending(int limit, Instant now);

  void markAttempt(UUID messageId, int attempt, Instant availableAt, String error);

  void markPublished(UUID messageId, Instant publishedAt);
}
