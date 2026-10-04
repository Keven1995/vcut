package com.vcut.api.shared.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TracingConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(name = "vcut.tracing.enabled", havingValue = "true")
  SdkTracerProvider sdkTracerProvider(
      @Value("${vcut.tracing.otlp-endpoint:http://localhost:4317}") String endpoint,
      @Value("${vcut.tracing.sample-probability:1.0}") double sampleProbability) {
    if (sampleProbability < 0 || sampleProbability > 1) {
      throw new IllegalArgumentException("trace sample probability must be between 0 and 1");
    }
    Resource resource =
        Resource.getDefault()
            .merge(
                Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "vcut-api")));
    return SdkTracerProvider.builder()
        .setResource(resource)
        .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(sampleProbability)))
        .addSpanProcessor(
            BatchSpanProcessor.builder(OtlpGrpcSpanExporter.builder().setEndpoint(endpoint).build())
                .build())
        .build();
  }

  @Bean
  @ConditionalOnProperty(name = "vcut.tracing.enabled", havingValue = "true")
  OpenTelemetry openTelemetry(SdkTracerProvider tracerProvider) {
    return OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
  }

  @Bean
  @ConditionalOnProperty(name = "vcut.tracing.enabled", havingValue = "true")
  Tracer vcutTracer(OpenTelemetry openTelemetry) {
    return openTelemetry.getTracer("com.vcut.api");
  }

  @Bean
  @ConditionalOnProperty(
      name = "vcut.tracing.enabled",
      havingValue = "false",
      matchIfMissing = true)
  Tracer noopTracer() {
    return OpenTelemetry.noop().getTracer("com.vcut.api");
  }
}
