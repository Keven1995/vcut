package com.vcut.api.shared.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vcut.api.shared.health.DependencyReadiness;
import com.vcut.api.shared.health.HealthController;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CorrelationIdFilterTest {

  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new HealthController(mock(DependencyReadiness.class)))
          .addFilters(new CorrelationIdFilter())
          .build();

  @Test
  void preservesAValidCorrelationIdInTheResponse() throws Exception {
    UUID correlationId = UUID.randomUUID();

    mockMvc
        .perform(get("/health/live").header(CorrelationIdFilter.HEADER_NAME, correlationId))
        .andExpect(status().isOk())
        .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, correlationId.toString()));

    assertThat(CorrelationContext.current()).isEmpty();
  }

  @Test
  void generatesAnIdForMissingOrInvalidInput() throws Exception {
    String responseHeader =
        mockMvc
            .perform(get("/health/live").header(CorrelationIdFilter.HEADER_NAME, "invalid"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader(CorrelationIdFilter.HEADER_NAME);

    assertThat(responseHeader).isNotNull();
    assertThatCodeIsUuid(responseHeader);
  }

  @Test
  void mapsCorrelationIdsToTraceIdsAndContinuesValidTraceparentHeaders() {
    UUID correlationId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    Context correlationParent = CorrelationTracing.parentContext(correlationId, null);
    String continuedTraceId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    Context continuedParent =
        CorrelationTracing.parentContext(
            correlationId, "00-" + continuedTraceId + "-bbbbbbbbbbbbbbbb-01");

    assertThat(Span.fromContext(correlationParent).getSpanContext().getTraceId())
        .isEqualTo(correlationId.toString().replace("-", ""));
    assertThat(Span.fromContext(continuedParent).getSpanContext().getTraceId())
        .isEqualTo(continuedTraceId);
  }

  @Test
  void exportsAnHttpSpanWithCorrelationTraceIdAndSafeAttributes() throws Exception {
    UUID correlationId = UUID.fromString("33333333-3333-4333-8333-333333333333");
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    SdkTracerProvider provider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    MockMvc tracedMvc =
        MockMvcBuilders.standaloneSetup(new HealthController(mock(DependencyReadiness.class)))
            .addFilters(new CorrelationIdFilter(provider.get("correlation-test")))
            .build();

    var response =
        tracedMvc
            .perform(get("/health/live").header(CorrelationIdFilter.HEADER_NAME, correlationId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();

    var span = exporter.getFinishedSpanItems().getFirst();
    assertThat(span.getName()).isEqualTo("http.server");
    assertThat(span.getTraceId()).isEqualTo(correlationId.toString().replace("-", ""));
    assertThat(
            span.getAttributes()
                .get(io.opentelemetry.api.common.AttributeKey.stringKey("vcut.correlation_id")))
        .isEqualTo(correlationId.toString());
    assertThat(
            span.getAttributes()
                .get(io.opentelemetry.api.common.AttributeKey.longKey("http.response.status_code")))
        .isEqualTo(200L);
    assertThat(
            span.getAttributes()
                .get(io.opentelemetry.api.common.AttributeKey.stringKey("http.target")))
        .isNull();
    assertThat(response.getHeader("traceparent"))
        .startsWith("00-" + correlationId.toString().replace("-", "") + "-");
    provider.close();
  }

  private static void assertThatCodeIsUuid(String value) {
    assertThat(UUID.fromString(value)).isNotNull();
  }
}
