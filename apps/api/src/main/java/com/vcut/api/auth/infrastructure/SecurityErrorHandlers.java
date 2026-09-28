package com.vcut.api.auth.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vcut.api.shared.api.ErrorResponse;
import com.vcut.api.shared.correlation.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class SecurityErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

  private final ObjectMapper objectMapper;

  public SecurityErrorHandlers(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    write(response, 401, "AUTHENTICATION_REQUIRED", "Authentication is required.");
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
      throws IOException {
    write(response, 403, "FORBIDDEN", "You are not allowed to access this resource.");
  }

  private void write(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    String traceId =
        CorrelationContext.current()
            .map(UUID::toString)
            .orElseGet(() -> UUID.randomUUID().toString());
    response.setStatus(status);
    response.setContentType("application/json");
    objectMapper.writeValue(response.getWriter(), new ErrorResponse(code, message, traceId));
  }
}
