package com.vcut.api.auth.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityContractTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void protectsProjectRoutesWithTheStandardErrorContract() throws Exception {
    mockMvc
        .perform(get("/api/projects"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  void exposesOnlyTheSignedPaymentWebhookRouteWithoutUserJwt() throws Exception {
    mockMvc
        .perform(
            post("/api/payments/sandbox/webhook")
                .header("X-Payment-Timestamp", "1791115200")
                .header("X-Payment-Signature", "sha256=" + "0".repeat(64))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("EXTERNAL_PROVIDER_ERROR"));
  }
}
