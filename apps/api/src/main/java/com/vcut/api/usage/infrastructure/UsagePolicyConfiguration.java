package com.vcut.api.usage.infrastructure;

import com.vcut.api.usage.application.PlanLimitsProvider;
import com.vcut.api.usage.domain.UsageCostRates;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class UsagePolicyConfiguration {

  @Bean
  PlanLimitsProvider planLimitsProvider(UsageProperties properties) {
    return properties::limitsFor;
  }

  @Bean
  UsageCostRates usageCostRates(UsageProperties properties) {
    return properties.costRates();
  }
}
