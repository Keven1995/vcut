package com.vcut.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vcut.api.security.application.SecurityOperationResolver;
import com.vcut.api.security.ratelimit.infrastructure.RateLimitProperties;
import com.vcut.api.shared.config.FeatureFlags;
import com.vcut.api.usage.application.PlanLimitsProvider;
import com.vcut.api.usage.domain.PlanCode;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ApiApplicationTest {

  @Autowired private FeatureFlags featureFlags;
  @Autowired private PlanLimitsProvider planLimitsProvider;
  @Autowired private RateLimitProperties rateLimitProperties;
  @Autowired private SecurityOperationResolver securityOperationResolver;
  @Autowired private MeterRegistry meterRegistry;

  @Test
  void contextLoads() {}

  @Test
  void experimentalFeatureFlagsAreDisabledInTheTestProfileByDefault() {
    assertThat(featureFlags.aiSmartCrop()).isFalse();
    assertThat(featureFlags.faceTracking()).isFalse();
    assertThat(featureFlags.multimodalAnalysis()).isFalse();
    assertThat(featureFlags.autoZoom()).isFalse();
    assertThat(featureFlags.externalVideoImport()).isFalse();
  }

  @Test
  void initialPlanLimitsAreLoadedFromEnvironmentConfiguration() {
    assertThat(planLimitsProvider.limitsFor(PlanCode.FREE).monthlyProcessingMinutes())
        .isEqualByComparingTo("60");
    assertThat(planLimitsProvider.limitsFor(PlanCode.PRO).monthlyProcessingMinutes())
        .isEqualByComparingTo("600");
    assertThat(planLimitsProvider.limitsFor(PlanCode.FREE).maxConcurrentJobs()).isEqualTo(1);
    assertThat(planLimitsProvider.limitsFor(PlanCode.PRO).maxConcurrentJobs()).isEqualTo(3);
  }

  @Test
  void everySensitiveOperationHasAnExplicitRateLimitPolicy() {
    assertThat(rateLimitProperties.operations().keySet())
        .containsAll(securityOperationResolver.operationNames());
  }

  @Test
  void registersQueueOutboxAndStorageCapacityMetrics() {
    assertThat(meterRegistry.find("vcut.outbox.pending").gauge()).isNotNull();
    assertThat(meterRegistry.find("vcut.outbox.oldest_pending_seconds").gauge()).isNotNull();
    assertThat(meterRegistry.find("vcut.jobs.status").tag("status", "FAILED").gauge()).isNotNull();
    assertThat(meterRegistry.find("vcut.storage.tracked_bytes").gauge()).isNotNull();
    assertThat(meterRegistry.find("vcut.storage.capacity_bytes").gauge()).isNotNull();
  }
}
