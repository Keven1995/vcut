package com.vcut.api.usage.application;

import com.vcut.api.usage.domain.PlanCode;

public interface PlanLimitsProvider {

  PlanLimits limitsFor(PlanCode planCode);
}
