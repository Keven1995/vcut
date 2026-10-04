package com.vcut.api.auth.infrastructure;

import com.vcut.api.auth.application.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private final JwtTokenService jwtTokenService;
  private final UserRepository userRepository;

  public JwtAuthenticationFilter(JwtTokenService jwtTokenService, UserRepository userRepository) {
    this.jwtTokenService = jwtTokenService;
    this.userRepository = userRepository;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String authorization = request.getHeader("Authorization");
    if (authorization != null && authorization.startsWith("Bearer ")) {
      authenticate(authorization.substring("Bearer ".length()).trim());
    }
    filterChain.doFilter(request, response);
  }

  private void authenticate(String token) {
    try {
      JwtTokenService.AuthenticatedToken authenticatedToken = jwtTokenService.parse(token);
      if (userRepository
          .findById(authenticatedToken.userId())
          .filter(user -> user.canAuthenticate())
          .isEmpty()) {
        SecurityContextHolder.clearContext();
        return;
      }
      SecurityContextHolder.getContext()
          .setAuthentication(
              UsernamePasswordAuthenticationToken.authenticated(
                  authenticatedToken.userId().toString(), null, java.util.List.of()));
    } catch (RuntimeException ignored) {
      SecurityContextHolder.clearContext();
    }
  }
}
