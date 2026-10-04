package com.vcut.api.subscription.infrastructure;

import com.vcut.api.subscription.application.PlanEntitlementSync;
import com.vcut.api.usage.application.RetentionApplicationService;
import com.vcut.api.usage.application.UsageApplicationService;
import com.vcut.api.usage.domain.PlanCode;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class UsagePlanEntitlementSyncAdapter implements PlanEntitlementSync {

  private final UsageApplicationService usageApplicationService;
  private final RetentionApplicationService retentionApplicationService;

  public UsagePlanEntitlementSyncAdapter(
      UsageApplicationService usageApplicationService,
      RetentionApplicationService retentionApplicationService) {
    this.usageApplicationService = usageApplicationService;
    this.retentionApplicationService = retentionApplicationService;
  }

  @Override
  public void synchronize(UUID userId, PlanCode planCode) {
    usageApplicationService.synchronizePlanSnapshot(userId, planCode);
    retentionApplicationService.refreshForUser(userId);
  }
}
