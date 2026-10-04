package com.vcut.api.usage.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vcut.api.usage.domain.PlanCode;
import com.vcut.api.usage.presentation.UsageController;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UsageControllerTest {

  @Test
  void returnsTheAuthenticatedUsersCurrentUsageSummary() throws Exception {
    UUID userId = UUID.randomUUID();
    UsageApplicationService usageApplicationService = mock(UsageApplicationService.class);
    when(usageApplicationService.currentSummary(userId))
        .thenReturn(
            new UsageSummary(
                PlanCode.FREE,
                Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-11-01T00:00:00Z"),
                BigDecimal.valueOf(60),
                BigDecimal.valueOf(2),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(48),
                1_024,
                1_024,
                5_000,
                1,
                BigDecimal.valueOf(0.25),
                1,
                1,
                List.of()));
    var mockMvc =
        MockMvcBuilders.standaloneSetup(new UsageController(usageApplicationService)).build();

    mockMvc
        .perform(get("/api/usage").principal(userId::toString))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.planCode").value("FREE"))
        .andExpect(jsonPath("$.remainingMinutes").value(48))
        .andExpect(jsonPath("$.retainedBytes").value(1_024))
        .andExpect(jsonPath("$.estimatedCost").value(0.25));

    verify(usageApplicationService).currentSummary(userId);
  }
}
