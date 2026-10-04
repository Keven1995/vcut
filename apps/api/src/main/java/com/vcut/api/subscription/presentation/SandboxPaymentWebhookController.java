package com.vcut.api.subscription.presentation;

import com.vcut.api.subscription.application.SubscriptionApplicationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments/sandbox/webhook")
public class SandboxPaymentWebhookController {

  private final SubscriptionApplicationService subscriptionApplicationService;

  public SandboxPaymentWebhookController(
      SubscriptionApplicationService subscriptionApplicationService) {
    this.subscriptionApplicationService = subscriptionApplicationService;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public WebhookProcessingResponse receive(
      @RequestHeader("X-Payment-Timestamp") String timestamp,
      @RequestHeader("X-Payment-Signature") String signature,
      @RequestBody String rawBody) {
    return WebhookProcessingResponse.from(
        subscriptionApplicationService.handleWebhook(timestamp, signature, rawBody));
  }
}
