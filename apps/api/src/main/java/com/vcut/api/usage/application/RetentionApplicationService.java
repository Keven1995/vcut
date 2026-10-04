package com.vcut.api.usage.application;

import com.vcut.api.usage.domain.RetainedObject;
import com.vcut.api.usage.domain.RetentionAssetType;
import com.vcut.api.usage.domain.UsageMetrics;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetentionApplicationService {

  private final RetentionRepository retentionRepository;
  private final UsageApplicationService usageApplicationService;
  private final Clock clock;

  @Autowired
  public RetentionApplicationService(
      RetentionRepository retentionRepository, UsageApplicationService usageApplicationService) {
    this(retentionRepository, usageApplicationService, Clock.systemUTC());
  }

  RetentionApplicationService(
      RetentionRepository retentionRepository,
      UsageApplicationService usageApplicationService,
      Clock clock) {
    this.retentionRepository = retentionRepository;
    this.usageApplicationService = usageApplicationService;
    this.clock = clock;
  }

  @Transactional
  public RetainedObject register(
      UUID userId, UUID projectId, String objectKey, RetentionAssetType assetType, long sizeBytes) {
    Instant now = clock.instant();
    var policy = usageApplicationService.limitsForUser(userId).retentionPolicy();
    RetainedObject retainedObject =
        retentionRepository.register(
            new RetainedObject(
                UUID.randomUUID(),
                userId,
                projectId,
                objectKey,
                assetType,
                sizeBytes,
                com.vcut.api.usage.domain.RetentionStatus.RETAINED,
                now.plus(policy.retentionFor(assetType)),
                0,
                null,
                now,
                null));
    usageApplicationService.recordMetrics(
        userId,
        UUID.nameUUIDFromBytes(objectKey.getBytes(StandardCharsets.UTF_8)),
        "STORAGE_ASSET_" + assetType.name(),
        1,
        new UsageMetrics(
            java.math.BigDecimal.ZERO,
            java.math.BigDecimal.ZERO,
            0,
            java.math.BigDecimal.ZERO,
            java.math.BigDecimal.ZERO,
            sizeBytes,
            0,
            0),
        "SUCCEEDED");
    return retainedObject;
  }

  @Transactional
  public void updateSize(String objectKey, long sizeBytes) {
    retentionRepository.updateSize(objectKey, sizeBytes);
  }

  @Transactional
  public void markDeleted(String objectKey) {
    retentionRepository.markDeleted(objectKey, clock.instant());
  }
}
