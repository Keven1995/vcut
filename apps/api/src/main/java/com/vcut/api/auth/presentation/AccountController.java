package com.vcut.api.auth.presentation;

import com.vcut.api.auth.application.AccountDeletionApplicationService;
import com.vcut.api.auth.infrastructure.AuthProperties;
import com.vcut.api.shared.errors.UnauthorizedException;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/account")
public class AccountController {

  private final AccountDeletionApplicationService accountDeletionApplicationService;
  private final AuthProperties authProperties;

  public AccountController(
      AccountDeletionApplicationService accountDeletionApplicationService,
      AuthProperties authProperties) {
    this.accountDeletionApplicationService = accountDeletionApplicationService;
    this.authProperties = authProperties;
  }

  @DeleteMapping
  public ResponseEntity<AccountDeletionResponse> delete(Principal principal) {
    UUID userId = userId(principal);
    Instant deleteAfter = accountDeletionApplicationService.request(userId);
    return ResponseEntity.accepted()
        .header(HttpHeaders.SET_COOKIE, deleteRefreshCookie().toString())
        .body(new AccountDeletionResponse(deleteAfter));
  }

  private ResponseCookie deleteRefreshCookie() {
    return ResponseCookie.from(AuthController.REFRESH_COOKIE, "")
        .httpOnly(true)
        .secure(authProperties.refreshCookieSecure())
        .sameSite("Lax")
        .path("/api/auth")
        .maxAge(Duration.ZERO)
        .build();
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

  public record AccountDeletionResponse(Instant scheduledFor) {}
}
