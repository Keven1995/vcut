package com.vcut.api.security.ratelimit.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vcut.api.security.application.SecurityOperationResolver;
import com.vcut.api.security.ratelimit.application.RateLimitApplicationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

class RateLimitInterceptorTest {

  private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void passesTheRemoteIpAndAuthenticatedUserToTheSensitiveOperationLimiter() throws Exception {
    RateLimitApplicationService rateLimitService = mock(RateLimitApplicationService.class);
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(USER_ID.toString(), null, List.of()));
    var mockMvc =
        MockMvcBuilders.standaloneSetup(new ProbeController())
            .addInterceptors(
                new RateLimitInterceptor(new SecurityOperationResolver(), rateLimitService))
            .build();

    mockMvc
        .perform(
            post("/api/videos/22222222-2222-4222-8222-222222222222/process")
                .with(
                    request -> {
                      request.setRemoteAddr("192.0.2.15");
                      return request;
                    }))
        .andExpect(status().isAccepted());

    verify(rateLimitService).enforce("video-process", "192.0.2.15", USER_ID);
  }

  @Test
  void doesNotThrottleAsynchronousStatusPolling() throws Exception {
    RateLimitApplicationService rateLimitService = mock(RateLimitApplicationService.class);
    var mockMvc =
        MockMvcBuilders.standaloneSetup(new ProbeController())
            .addInterceptors(
                new RateLimitInterceptor(new SecurityOperationResolver(), rateLimitService))
            .build();

    mockMvc
        .perform(get("/api/jobs/22222222-2222-4222-8222-222222222222"))
        .andExpect(status().isOk());

    verifyNoInteractions(rateLimitService);
  }

  @RestController
  static class ProbeController {
    @PostMapping("/api/videos/{videoId}/process")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void process() {}

    @GetMapping("/api/jobs/{jobId}")
    public void status() {}
  }
}
