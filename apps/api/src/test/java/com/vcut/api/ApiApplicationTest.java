package com.vcut.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vcut.api.shared.config.FeatureFlags;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ApiApplicationTest {

  @Autowired private FeatureFlags featureFlags;

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
}
