package com.vcut.api.subscription.presentation;

import com.vcut.api.shared.errors.UnauthorizedException;
import com.vcut.api.shared.errors.ValidationException;
import com.vcut.api.subscription.application.SubscriptionApplicationService;
import com.vcut.api.usage.domain.PlanCode;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.Locale;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

  private final SubscriptionApplicationService subscriptionApplicationService;

  public SubscriptionController(SubscriptionApplicationService subscriptionApplicationService) {
    this.subscriptionApplicationService = subscriptionApplicationService;
  }

  @GetMapping
  public SubscriptionOverviewResponse overview(Principal principal) {
    return SubscriptionOverviewResponse.from(
        subscriptionApplicationService.overview(userId(principal)));
  }

  @PostMapping("/checkout")
  public SubscriptionOverviewResponse createCheckout(
      Principal principal, @Valid @RequestBody CheckoutRequest request) {
    subscriptionApplicationService.createCheckout(userId(principal), planCode(request.planCode()));
    return SubscriptionOverviewResponse.from(
        subscriptionApplicationService.overview(userId(principal)));
  }

  @PostMapping("/{subscriptionId}/cancel")
  public SubscriptionOverviewResponse cancel(
      Principal principal, @PathVariable UUID subscriptionId) {
    subscriptionApplicationService.cancelAtPeriodEnd(userId(principal), subscriptionId);
    return SubscriptionOverviewResponse.from(
        subscriptionApplicationService.overview(userId(principal)));
  }

  @PostMapping("/checkouts/{checkoutId}/sandbox-confirm")
  public SubscriptionOverviewResponse confirmSandboxCheckout(
      Principal principal, @PathVariable UUID checkoutId) {
    subscriptionApplicationService.confirmSandboxCheckout(userId(principal), checkoutId);
    return SubscriptionOverviewResponse.from(
        subscriptionApplicationService.overview(userId(principal)));
  }

  private static PlanCode planCode(String value) {
    try {
      return PlanCode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new ValidationException("Requested subscription plan is not supported.");
    }
  }

  private static UUID userId(Principal principal) {
    if (principal == null || principal.getName() == null) {
      throw new UnauthorizedException("Authentication is required.");
    }
    try {
      return UUID.fromString(principal.getName());
    } catch (IllegalArgumentException exception) {
      throw new UnauthorizedException("Authentication is invalid.");
    }
  }
}
