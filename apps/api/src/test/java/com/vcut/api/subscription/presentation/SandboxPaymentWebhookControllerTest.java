package com.vcut.api.subscription.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vcut.api.subscription.application.SubscriptionApplicationService;
import com.vcut.api.subscription.application.WebhookProcessingResult;
import com.vcut.api.subscription.domain.BillingEventStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SandboxPaymentWebhookControllerTest {

  @Test
  void acceptsOnlyTheSignedWebhookContractAndAcknowledgesItsProcessingState() throws Exception {
    SubscriptionApplicationService service = mock(SubscriptionApplicationService.class);
    when(service.handleWebhook("1791115200", "sha256=signature", "{\"eventVersion\":1}"))
        .thenReturn(new WebhookProcessingResult(false, BillingEventStatus.APPLIED));
    var mockMvc =
        MockMvcBuilders.standaloneSetup(new SandboxPaymentWebhookController(service)).build();

    mockMvc
        .perform(
            post("/api/payments/sandbox/webhook")
                .header("X-Payment-Timestamp", "1791115200")
                .header("X-Payment-Signature", "sha256=signature")
                .contentType("application/json")
                .content("{\"eventVersion\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.duplicate").value(false))
        .andExpect(jsonPath("$.status").value("APPLIED"));

    verify(service).handleWebhook("1791115200", "sha256=signature", "{\"eventVersion\":1}");
  }
}
