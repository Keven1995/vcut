package com.vcut.api.shared.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER_NAME = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    UUID correlationId = resolve(request.getHeader(HEADER_NAME));
    CorrelationContext.set(correlationId);
    MDC.put(MDC_KEY, correlationId.toString());
    response.setHeader(HEADER_NAME, correlationId.toString());

    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
      CorrelationContext.clear();
    }
  }

  private UUID resolve(String headerValue) {
    if (headerValue == null || headerValue.isBlank()) {
      return UUID.randomUUID();
    }
    try {
      return UUID.fromString(headerValue.trim());
    } catch (IllegalArgumentException exception) {
      return UUID.randomUUID();
    }
  }
}
