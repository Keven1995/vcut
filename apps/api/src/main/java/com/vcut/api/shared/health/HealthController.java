package com.vcut.api.shared.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
  @Operation(summary = "Verifica se o processo está vivo")
  @ApiResponse(responseCode = "200", description = "Processo disponível")
  public ResponseEntity<HealthResponse> live() {
    return ResponseEntity.ok(new HealthResponse("UP", Map.of()));
  }

  @GetMapping("/ready")
  @Operation(summary = "Verifica se as dependências estão prontas")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Dependências disponíveis"),
    @ApiResponse(responseCode = "503", description = "Uma ou mais dependências indisponíveis")
  })
  public ResponseEntity<HealthResponse> ready() {
    Map<String, String> dependencies = dependencyReadiness.check();
    boolean available = dependencies.values().stream().allMatch("UP"::equals);
    HealthResponse response = new HealthResponse(available ? "UP" : "DOWN", dependencies);
    return ResponseEntity.status(available ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
        .body(response);
  }

  public record HealthResponse(String status, Map<String, String> dependencies) {}
}
