package com.vcut.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vcut.api.shared.config.FeatureFlags;
import com.vcut.api.usage.application.PlanLimitsProvider;
import com.vcut.api.usage.domain.PlanCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ApiApplicationTest {

  @Autowired private FeatureFlags featureFlags;
  @Autowired private PlanLimitsProvider planLimitsProvider;

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
}
