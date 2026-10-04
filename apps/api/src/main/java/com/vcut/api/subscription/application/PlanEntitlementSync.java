package com.vcut.api.subscription.application;

import com.vcut.api.usage.domain.PlanCode;
import java.util.UUID;

public interface PlanEntitlementSync {

  void synchronize(UUID userId, PlanCode planCode);
}
