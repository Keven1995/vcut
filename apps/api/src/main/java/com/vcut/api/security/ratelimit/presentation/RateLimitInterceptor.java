package com.vcut.api.security.ratelimit.presentation;

import com.vcut.api.security.application.SecurityOperationResolver;
import com.vcut.api.security.ratelimit.application.RateLimitApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

  private final SecurityOperationResolver operationResolver;
  private final RateLimitApplicationService rateLimitApplicationService;

  public RateLimitInterceptor(
      SecurityOperationResolver operationResolver,
      RateLimitApplicationService rateLimitApplicationService) {
    this.operationResolver = operationResolver;
    this.rateLimitApplicationService = rateLimitApplicationService;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod)) {
      return true;
    }
    Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    String routePattern = route == null ? null : route.toString();
    operationResolver
        .resolve(request.getMethod(), routePattern)
        .ifPresent(
            operation ->
                rateLimitApplicationService.enforce(
                    operation, request.getRemoteAddr(), authenticatedUserId()));
    return true;
  }

  private static UUID authenticatedUserId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      return null;
    }
    try {
      return UUID.fromString(authentication.getName());
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }
}
