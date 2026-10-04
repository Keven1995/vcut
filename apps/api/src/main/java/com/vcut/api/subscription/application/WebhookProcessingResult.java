package com.vcut.api.subscription.application;

import com.vcut.api.subscription.domain.BillingEventStatus;

public record WebhookProcessingResult(boolean duplicate, BillingEventStatus status) {}
