package com.vcut.api.shared.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vcut.api.shared.health.DependencyReadiness;
import com.vcut.api.shared.health.HealthController;
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

  private static void assertThatCodeIsUuid(String value) {
    assertThat(UUID.fromString(value)).isNotNull();
  }
}
