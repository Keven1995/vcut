package com.vcut.api.auth.presentation;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vcut.api.auth.application.AuthApplicationService;
import com.vcut.api.auth.application.AuthResult;
import com.vcut.api.auth.infrastructure.AuthProperties;
import com.vcut.api.shared.correlation.CorrelationIdFilter;
import com.vcut.api.shared.errors.GlobalExceptionHandler;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTest {

  private final AuthApplicationService authService = mock(AuthApplicationService.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    AuthProperties properties =
        new AuthProperties(
            "test-secret-with-at-least-32-characters-long",
            Duration.ofMinutes(15),
            Duration.ofDays(30),
            false);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new AuthController(authService, properties))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();
  }

  @Test
  void returnsAccessTokenAndHttpOnlyRefreshCookieWithoutExposingRefreshTokenInJson()
      throws Exception {
    when(authService.login("person@example.com", "strong-password"))
        .thenReturn(new AuthResult("access-token", "refresh-token", 900));

    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"person@example.com\",\"password\":\"strong-password\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value("access-token"))
        .andExpect(jsonPath("$.expiresInSeconds").value(900))
        .andExpect(jsonPath("$.refreshToken").doesNotExist())
        .andExpect(cookie().httpOnly(AuthController.REFRESH_COOKIE, true));
  }

  @Test
  void clearsRefreshCookieOnLogout() throws Exception {
    mockMvc
        .perform(
            delete("/api/auth/logout")
                .cookie(
                    new jakarta.servlet.http.Cookie(
                        AuthController.REFRESH_COOKIE, "refresh-token")))
        .andExpect(status().isNoContent())
        .andExpect(cookie().maxAge(AuthController.REFRESH_COOKIE, 0));

    verify(authService).logout(anyString());
  }

  @Test
  void rejectsMalformedJsonAsBadRequest() throws Exception {
    mockMvc
        .perform(post("/api/auth/login").contentType("application/json").content("{invalid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }
}
