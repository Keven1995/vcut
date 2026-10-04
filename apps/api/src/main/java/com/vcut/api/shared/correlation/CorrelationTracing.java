package com.vcut.api.shared.correlation;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import java.util.Map;
import java.util.UUID;

public final class CorrelationTracing {

  private CorrelationTracing() {}

  public static Span startSpan(
      Tracer tracer, String name, UUID correlationId, String traceparent, SpanKind kind) {
    Span span =
        tracer
            .spanBuilder(name)
            .setParent(parentContext(correlationId, traceparent))
            .setSpanKind(kind)
            .startSpan();
    span.setAttribute("vcut.correlation_id", correlationId.toString());
    return span;
  }

  public static Context parentContext(UUID correlationId, String traceparent) {
    if (traceparent != null && !traceparent.isBlank()) {
      Context extracted =
          W3CTraceContextPropagator.getInstance()
              .extract(Context.root(), Map.of("traceparent", traceparent), MAP_GETTER);
      if (Span.fromContext(extracted).getSpanContext().isValid()) {
        return extracted;
      }
    }

    String traceId = correlationId.toString().replace("-", "");
    String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    SpanContext parent =
        SpanContext.createFromRemoteParent(
            traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault());
    return Context.root().with(Span.wrap(parent));
  }

  public static String currentTraceparent() {
    return traceparent(Span.current().getSpanContext());
  }

  public static String traceparent(SpanContext context) {
    if (!context.isValid()) {
      return null;
    }
    return "00-"
        + context.getTraceId()
        + "-"
        + context.getSpanId()
        + "-"
        + String.format("%02x", context.getTraceFlags().asByte());
  }

  public static void markError(Span span, Throwable error) {
    span.setAttribute("error.type", error.getClass().getSimpleName());
    span.setStatus(StatusCode.ERROR);
  }

  private static final io.opentelemetry.context.propagation.TextMapGetter<Map<String, String>>
      MAP_GETTER =
          new io.opentelemetry.context.propagation.TextMapGetter<>() {
            @Override
            public Iterable<String> keys(Map<String, String> carrier) {
              return carrier.keySet();
            }

            @Override
            public String get(Map<String, String> carrier, String key) {
              return carrier.get(key);
            }
          };
}
