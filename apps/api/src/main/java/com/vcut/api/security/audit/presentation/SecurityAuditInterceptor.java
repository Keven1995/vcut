package com.vcut.api.security.audit.presentation;

import com.vcut.api.security.application.SecurityOperationResolver;
import com.vcut.api.security.audit.application.SecurityAuditService;
import com.vcut.api.security.audit.domain.SecurityAuditOutcome;
import com.vcut.api.shared.correlation.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

@Component
public class SecurityAuditInterceptor implements HandlerInterceptor {

  private static final Logger LOGGER = LoggerFactory.getLogger(SecurityAuditInterceptor.class);
  private static final String EVENT_TYPE_ATTRIBUTE =
      SecurityAuditInterceptor.class.getName() + ".eventType";
  private static final String ROUTE_ATTRIBUTE = SecurityAuditInterceptor.class.getName() + ".route";

  private final SecurityOperationResolver operationResolver;
  private final SecurityAuditService securityAuditService;

  public SecurityAuditInterceptor(
      SecurityOperationResolver operationResolver, SecurityAuditService securityAuditService) {
    this.operationResolver = operationResolver;
    this.securityAuditService = securityAuditService;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod)) {
      return true;
    }
    String route = routePattern(request);
    operationResolver
        .resolve(request.getMethod(), route)
        .ifPresent(
            operation -> {
              request.setAttribute(EVENT_TYPE_ATTRIBUTE, eventType(operation));
              request.setAttribute(ROUTE_ATTRIBUTE, route);
            });
    return true;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request,
      HttpServletResponse response,
      Object handler,
      Exception exception) {
    Object eventTypeAttribute = request.getAttribute(EVENT_TYPE_ATTRIBUTE);
    Object routeAttribute = request.getAttribute(ROUTE_ATTRIBUTE);
    if (!(eventTypeAttribute instanceof String eventType)
        || !(routeAttribute instanceof String route)) {
      return;
    }
    try {
      securityAuditService.record(
          eventType,
          authenticatedUserId(),
          route,
          outcome(response.getStatus()),
          response.getStatus(),
          CorrelationContext.current().orElse(null));
    } catch (RuntimeException auditFailure) {
      LOGGER.warn(
          "security_audit_persist_failed eventType={} errorType={}",
          eventType,
          auditFailure.getClass().getSimpleName());
    }
  }

  private static String routePattern(HttpServletRequest request) {
    Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    return route == null ? "/unmatched" : route.toString();
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

  private static String eventType(String operation) {
    return operation.replace('-', '_').toUpperCase(Locale.ROOT);
  }

  private static SecurityAuditOutcome outcome(int httpStatus) {
    if (httpStatus >= 200 && httpStatus < 300) {
      return SecurityAuditOutcome.SUCCESS;
    }
    if (httpStatus == 401 || httpStatus == 403 || httpStatus == 429) {
      return SecurityAuditOutcome.DENIED;
    }
    return SecurityAuditOutcome.FAILURE;
  }
}
