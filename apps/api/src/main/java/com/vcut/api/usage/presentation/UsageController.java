package com.vcut.api.usage.presentation;

import com.vcut.api.shared.errors.UnauthorizedException;
import com.vcut.api.usage.application.UsageApplicationService;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/usage")
public class UsageController {

  private final UsageApplicationService usageApplicationService;

  public UsageController(UsageApplicationService usageApplicationService) {
    this.usageApplicationService = usageApplicationService;
  }

  @GetMapping
  public UsageSummaryResponse current(Principal authentication) {
    return UsageSummaryResponse.from(
        usageApplicationService.currentSummary(userId(authentication)));
  }

  private static UUID userId(Principal authentication) {
    if (authentication == null || authentication.getName() == null) {
      throw new UnauthorizedException("Authentication is required.");
    }
    try {
      return UUID.fromString(authentication.getName());
    } catch (IllegalArgumentException exception) {
      throw new UnauthorizedException("Authentication is invalid.");
    }
  }
}
