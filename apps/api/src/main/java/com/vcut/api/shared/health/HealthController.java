package com.vcut.api.shared.health;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/health")
public class HealthController {

  private final DependencyReadiness dependencyReadiness;

  public HealthController(DependencyReadiness dependencyReadiness) {
    this.dependencyReadiness = dependencyReadiness;
  }

  @GetMapping("/live")
  public ResponseEntity<HealthResponse> live() {
    return ResponseEntity.ok(new HealthResponse("UP", Map.of()));
  }

  @GetMapping("/ready")
  public ResponseEntity<HealthResponse> ready() {
    Map<String, String> dependencies = dependencyReadiness.check();
    boolean available = dependencies.values().stream().allMatch("UP"::equals);
    HealthResponse response = new HealthResponse(available ? "UP" : "DOWN", dependencies);
    return ResponseEntity.status(available ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
        .body(response);
  }

  public record HealthResponse(String status, Map<String, String> dependencies) {}
}
