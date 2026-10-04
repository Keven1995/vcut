package com.vcut.api.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vcut.api.auth.application.UserRepository;
import com.vcut.api.auth.domain.User;
import com.vcut.api.auth.domain.UserStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

  private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void refusesExistingAccessTokensForDeletionPendingAccounts() throws Exception {
    JwtTokenService tokens = mock(JwtTokenService.class);
    UserRepository users = mock(UserRepository.class);
    when(tokens.parse("access-token"))
        .thenReturn(new JwtTokenService.AuthenticatedToken(USER_ID, "person@example.com"));
    when(users.findById(USER_ID)).thenReturn(Optional.of(user(UserStatus.DELETION_PENDING)));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer access-token");
    MockFilterChain chain = new MockFilterChain();

    new JwtAuthenticationFilter(tokens, users)
        .doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void authenticatesOnlyActiveAccounts() throws Exception {
    JwtTokenService tokens = mock(JwtTokenService.class);
    UserRepository users = mock(UserRepository.class);
    when(tokens.parse("access-token"))
        .thenReturn(new JwtTokenService.AuthenticatedToken(USER_ID, "person@example.com"));
    when(users.findById(USER_ID)).thenReturn(Optional.of(user(UserStatus.ACTIVE)));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer access-token");

    new JwtAuthenticationFilter(tokens, users)
        .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
  }

  private static User user(UserStatus status) {
    return new User(USER_ID, "person@example.com", "person@example.com", "hash", status, NOW, NOW);
  }
}
