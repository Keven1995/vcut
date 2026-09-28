package com.vcut.api.shared.health;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HealthControllerTest {

  private final DependencyReadiness dependencyReadiness = mock(DependencyReadiness.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new HealthController(dependencyReadiness)).build();
  }

  @Test
  void liveDoesNotRequireDependencies() throws Exception {
    mockMvc
        .perform(get("/health/live"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void readyReturnsServiceUnavailableWhenDependencyIsDown() throws Exception {
    when(dependencyReadiness.check())
        .thenReturn(Map.of("postgres", "UP", "rabbitmq", "DOWN", "storage", "UP"));

    mockMvc
        .perform(get("/health/ready"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value("DOWN"))
        .andExpect(jsonPath("$.dependencies.rabbitmq").value("DOWN"));
  }
}
