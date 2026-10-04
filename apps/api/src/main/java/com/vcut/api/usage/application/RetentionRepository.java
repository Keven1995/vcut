package com.vcut.api.usage.application;

import com.vcut.api.usage.domain.RetainedObject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RetentionRepository {

  RetainedObject register(RetainedObject retainedObject);

  Optional<RetainedObject> findByKey(String objectKey);

  List<RetainedObject> findRetainedForUser(UUID userId);

  void updateExpiration(UUID id, Instant expiresAt);

  void updateSize(String objectKey, long sizeBytes);

  void markDeleted(String objectKey, Instant deletedAt);

  List<RetainedObject> claimExpired(Instant now, int limit);

  List<RetainedObject> findExpired(Instant now, int limit);

  void markDeleted(UUID id, Instant deletedAt);

  void recordDeleteFailure(UUID id, String failureCode);
}
