package com.vcut.api.shared.correlation;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
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
  private final Tracer tracer;

  public CorrelationIdFilter() {
    this(io.opentelemetry.api.OpenTelemetry.noop().getTracer("vcut-api"));
  }

  public CorrelationIdFilter(Tracer tracer) {
    this.tracer = tracer;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    UUID correlationId = resolve(request.getHeader(HEADER_NAME));
    CorrelationContext.set(correlationId);
    MDC.put(MDC_KEY, correlationId.toString());
    response.setHeader(HEADER_NAME, correlationId.toString());
    Span span =
        CorrelationTracing.startSpan(
            tracer,
            "http.server",
            correlationId,
            request.getHeader("traceparent"),
            SpanKind.SERVER);
    span.setAttribute("http.request.method", request.getMethod());

    try (Scope ignored = span.makeCurrent()) {
      filterChain.doFilter(request, response);
    } catch (IOException | ServletException | RuntimeException failure) {
      CorrelationTracing.markError(span, failure);
      throw failure;
    } finally {
      span.setAttribute("http.response.status_code", response.getStatus());
      if (response.getStatus() >= 500) {
        span.setStatus(StatusCode.ERROR);
      }
      String traceparent = CorrelationTracing.traceparent(span.getSpanContext());
      if (traceparent != null) {
        response.setHeader("traceparent", traceparent);
      }
      span.end();
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
