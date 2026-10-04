package com.vcut.api.usage.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vcut.api.usage.domain.RetainedObject;
import com.vcut.api.usage.domain.RetentionAssetType;
import com.vcut.api.usage.domain.RetentionPolicy;
import com.vcut.api.usage.domain.RetentionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RetentionApplicationServiceTest {

  @Test
  void planChangeRecalculatesUnclaimedObjectExpiryFromItsCreationTime() {
    UUID userId = UUID.randomUUID();
    UUID objectId = UUID.randomUUID();
    UUID projectId = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-10-01T00:00:00Z");
    RetainedObject object =
        new RetainedObject(
            objectId,
            userId,
            projectId,
            "users/u/projects/p/source/original.mp4",
            RetentionAssetType.ORIGINAL,
            1_024,
            RetentionStatus.RETAINED,
            createdAt.plusSeconds(30L * 24 * 60 * 60),
            0,
            null,
            createdAt,
            null);
    RetentionRepository retentionRepository = mock(RetentionRepository.class);
    UsageApplicationService usageApplicationService = mock(UsageApplicationService.class);
    when(retentionRepository.findRetainedForUser(userId)).thenReturn(List.of(object));
    when(usageApplicationService.limitsForUser(userId))
        .thenReturn(
            new PlanLimits(
                BigDecimal.valueOf(600),
                2_000_000,
                7_200,
                50_000_000,
                3,
                3_840,
                3_840,
                5,
                new RetentionPolicy(90, 30, 14, 30, 90, 90, 14)));
    RetentionApplicationService service =
        new RetentionApplicationService(retentionRepository, usageApplicationService);

    service.refreshForUser(userId);

    verify(retentionRepository)
        .updateExpiration(objectId, createdAt.plusSeconds(90L * 24 * 60 * 60));
  }
}
