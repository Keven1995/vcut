package com.vcut.api.subscription.presentation;

import com.vcut.api.subscription.application.WebhookProcessingResult;

public record WebhookProcessingResponse(boolean duplicate, String status) {

  public static WebhookProcessingResponse from(WebhookProcessingResult result) {
    return new WebhookProcessingResponse(result.duplicate(), result.status().name());
  }
}
