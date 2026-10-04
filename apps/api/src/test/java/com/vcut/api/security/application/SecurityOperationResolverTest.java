package com.vcut.api.security.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecurityOperationResolverTest {

  private final SecurityOperationResolver resolver = new SecurityOperationResolver();

  @Test
  void matchesSensitiveSubmissionActionsButLeavesAsynchronousStatusPollingUnthrottled() {
    assertThat(resolver.resolve("POST", "/api/videos/{videoId}/process")).contains("video-process");
    assertThat(resolver.resolve("POST", "/api/subscriptions/checkout"))
        .contains("subscription-checkout");
    assertThat(resolver.resolve("GET", "/api/jobs/{jobId}")).isEmpty();
    assertThat(resolver.resolve("GET", "/api/videos/{videoId}")).isEmpty();
  }
}
