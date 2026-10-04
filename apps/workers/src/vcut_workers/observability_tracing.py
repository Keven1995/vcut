import os
from uuid import UUID, uuid4

from opentelemetry import trace
from opentelemetry.context import Context
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.trace import SpanContext
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator


def configure_tracing() -> None:
    endpoint = os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT", "").strip()
    if not endpoint:
        return
    provider = TracerProvider(resource=Resource.create({"service.name": "vcut-workers"}))
    provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=endpoint)))
    trace.set_tracer_provider(provider)


def parent_context(correlation_id: UUID, traceparent: str | None) -> Context:
    parent_header = traceparent
    if not parent_header:
        parent_header = f"00-{correlation_id.hex}-{uuid4().hex[:16]}-01"
    return TraceContextTextMapPropagator().extract({"traceparent": parent_header})


def current_traceparent() -> str | None:
    span_context: SpanContext = trace.get_current_span().get_span_context()
    if not span_context.is_valid:
        return None
    return (
        f"00-{span_context.trace_id:032x}-{span_context.span_id:016x}-"
        f"{int(span_context.trace_flags):02x}"
    )


__all__ = ["configure_tracing", "current_traceparent", "parent_context"]
