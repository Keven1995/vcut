package com.vcut.api.subscription.presentation;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vcut.api.subscription.application.SubscriptionApplicationService;
import com.vcut.api.subscription.application.SubscriptionOverview;
import com.vcut.api.subscription.domain.BillingReconciliation;
import com.vcut.api.subscription.domain.PlanBenefits;
import com.vcut.api.subscription.domain.SubscriptionPlan;
import com.vcut.api.usage.domain.PlanCode;
import com.vcut.api.usage.domain.RetentionPolicy;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SubscriptionControllerTest {

  @Test
  void checkoutUsesTheAuthenticatedPrincipalAndReturnsConfiguredPlanBenefits() throws Exception {
    UUID authenticatedUserId = UUID.randomUUID();
    SubscriptionApplicationService service = mock(SubscriptionApplicationService.class);
    when(service.overview(authenticatedUserId)).thenReturn(overview());
    var mockMvc = MockMvcBuilders.standaloneSetup(new SubscriptionController(service)).build();

    mockMvc
        .perform(
            post("/api/subscriptions/checkout")
                .principal(authenticatedUserId::toString)
                .contentType("application/json")
                .content("{\"planCode\":\"pro\",\"userId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sandboxEnabled").value(true))
        .andExpect(jsonPath("$.plans[1].planCode").value("PRO"))
        .andExpect(jsonPath("$.plans[1].workerPriority").value(5))
        .andExpect(jsonPath("$.currentSubscription").doesNotExist());

    verify(service).createCheckout(eq(authenticatedUserId), eq(PlanCode.PRO));
    verify(service).overview(authenticatedUserId);
  }

  private static SubscriptionOverview overview() {
    Currency currency = Currency.getInstance("USD");
    PlanBenefits freeBenefits = benefits(60, 0);
    PlanBenefits proBenefits = benefits(600, 5);
    return new SubscriptionOverview(
        true,
        List.of(
            new SubscriptionPlan(PlanCode.FREE, "Free", 0, currency, freeBenefits),
            new SubscriptionPlan(PlanCode.PRO, "Pro", 1_200, currency, proBenefits)),
        null,
        List.of(),
        List.of(),
        new BillingReconciliation(currency, 0, 0, 0, 0));
  }

  private static PlanBenefits benefits(long minutes, int workerPriority) {
    return new PlanBenefits(
        BigDecimal.valueOf(minutes),
        500,
        3_600,
        5_000,
        1,
        workerPriority,
        1_920,
        1_920,
        new RetentionPolicy(30, 7, 3, 7, 30, 30, 7));
  }
}
