package com.vcut.api.subscription.presentation;

import jakarta.validation.constraints.NotBlank;

public record CheckoutRequest(@NotBlank String planCode) {}
