package com.vcut.api.security.audit.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.vcut.api.security.application.SecurityOperationResolver;
import com.vcut.api.security.audit.application.SecurityAuditService;
import com.vcut.api.security.audit.domain.SecurityAuditOutcome;
import com.vcut.api.shared.correlation.CorrelationContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

class SecurityAuditInterceptorTest {

  private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID CORRELATION_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");

  @AfterEach
  void clearRequestContext() {
    SecurityContextHolder.clearContext();
    CorrelationContext.clear();
  }

  @Test
  void auditUsesRouteTemplateAndDoesNotPersistRequestPathOrBody() throws Exception {
    SecurityAuditService audit = mock(SecurityAuditService.class);
    SecurityAuditInterceptor interceptor =
        new SecurityAuditInterceptor(new SecurityOperationResolver(), audit);
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(USER_ID.toString(), null, List.of()));
    CorrelationContext.set(CORRELATION_ID);
    MockHttpServletRequest request =
        new MockHttpServletRequest("DELETE", "/api/videos/33333333-3333-4333-8333-333333333333");
    request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/videos/{videoId}");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(204);

    interceptor.preHandle(request, response, handler());
    interceptor.afterCompletion(request, response, handler(), null);

    verify(audit)
        .record(
            org.mockito.ArgumentMatchers.eq("VIDEO_DELETE"),
            org.mockito.ArgumentMatchers.eq(USER_ID),
            org.mockito.ArgumentMatchers.eq("/api/videos/{videoId}"),
            org.mockito.ArgumentMatchers.eq(SecurityAuditOutcome.SUCCESS),
            org.mockito.ArgumentMatchers.eq(204),
            org.mockito.ArgumentMatchers.eq(CORRELATION_ID));
  }

  @Test
  void auditsFailedLoginWithoutRecordingTheSuppliedEmailOrPassword() throws Exception {
    SecurityAuditService audit = mock(SecurityAuditService.class);
    SecurityAuditInterceptor interceptor =
        new SecurityAuditInterceptor(new SecurityOperationResolver(), audit);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
    request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/auth/login");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(401);

    interceptor.preHandle(request, response, handler());
    interceptor.afterCompletion(request, response, handler(), null);

    verify(audit)
        .record(
            org.mockito.ArgumentMatchers.eq("AUTH_LOGIN"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("/api/auth/login"),
            org.mockito.ArgumentMatchers.eq(SecurityAuditOutcome.DENIED),
            org.mockito.ArgumentMatchers.eq(401),
            org.mockito.ArgumentMatchers.isNull());
  }

  private HandlerMethod handler() throws NoSuchMethodException {
    return new HandlerMethod(this, getClass().getDeclaredMethod("mappedHandler"));
  }

  public void mappedHandler() {}
}
