package com.vcut.api.shared.errors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vcut.api.shared.correlation.CorrelationIdFilter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class ErrorContractTest {

  private final UUID correlationId = UUID.randomUUID();
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ErrorContractController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();
  }

  @Test
  void returnsValidationContract() throws Exception {
    assertError("/contract/validation", 400, "VALIDATION_ERROR");
  }

  @Test
  void returnsUnauthorizedContract() throws Exception {
    assertError("/contract/unauthorized", 401, "AUTHENTICATION_REQUIRED");
  }

  @Test
  void returnsNotFoundContract() throws Exception {
    assertError("/contract/not-found", 404, "RESOURCE_NOT_FOUND");
  }

  @Test
  void returnsConflictContract() throws Exception {
    assertError("/contract/conflict", 409, "RESOURCE_CONFLICT");
  }

  @Test
  void returnsRateLimitContract() throws Exception {
    assertError("/contract/rate-limit", 429, "RATE_LIMIT_EXCEEDED");
  }

  private void assertError(String path, int status, String code) throws Exception {
    mockMvc
        .perform(get(path).header(CorrelationIdFilter.HEADER_NAME, correlationId))
        .andExpect(status().is(status))
        .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, correlationId.toString()))
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.message").isNotEmpty())
        .andExpect(jsonPath("$.traceId").value(correlationId.toString()));
  }

  @RestController
  @RequestMapping("/contract")
  static class ErrorContractController {

    @GetMapping("/validation")
    void validation() {
      throw new ValidationException("Invalid request.");
    }

    @GetMapping("/unauthorized")
    void unauthorized() {
      throw new UnauthorizedException("Authentication required.");
    }

    @GetMapping("/not-found")
    void notFound() {
      throw new ResourceNotFoundException("Resource not found.");
    }

    @GetMapping("/conflict")
    void conflict() {
      throw new ConflictException("Resource conflict.");
    }

    @GetMapping("/rate-limit")
    void rateLimit() {
      throw new RateLimitExceededException("Rate limit exceeded.");
    }
  }
}
