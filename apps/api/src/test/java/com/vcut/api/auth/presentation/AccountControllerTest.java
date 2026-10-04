package com.vcut.api.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vcut.api.auth.application.AccountDeletionApplicationService;
import com.vcut.api.auth.infrastructure.AuthProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AccountControllerTest {

  @Test
  void acceptsDeletionAndExpiresTheRefreshCookie() {
    UUID userId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    Instant deleteAfter = Instant.parse("2026-11-03T12:00:00Z");
    AccountDeletionApplicationService deletionService =
        mock(AccountDeletionApplicationService.class);
    AuthProperties authProperties =
        new AuthProperties(
            "test-secret-with-at-least-32-characters-long",
            Duration.ofMinutes(15),
            Duration.ofDays(30),
            false);
    when(deletionService.request(userId)).thenReturn(deleteAfter);
    var controller = new AccountController(deletionService, authProperties);

    var response = controller.delete(() -> userId.toString());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody().scheduledFor()).isEqualTo(deleteAfter);
    assertThat(response.getHeaders().getFirst("Set-Cookie"))
        .contains("vcut_refresh_token=")
        .contains("Max-Age=0");
    verify(deletionService).request(userId);
  }
}
