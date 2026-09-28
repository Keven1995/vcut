package com.vcut.api.shared.health;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DefaultDependencyReadiness implements DependencyReadiness {

  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(1);

  private final DependencyProperties properties;
  private final HttpClient httpClient;

  public DefaultDependencyReadiness(DependencyProperties properties) {
    this.properties = properties;
    this.httpClient = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build();
  }

  @Override
  public Map<String, String> check() {
    Map<String, String> dependencies = new LinkedHashMap<>();
    dependencies.put("postgres", checkTcp(properties.postgresHost(), properties.postgresPort()));
    dependencies.put("rabbitmq", checkTcp(properties.rabbitmqHost(), properties.rabbitmqPort()));
    dependencies.put("storage", checkStorage());
    return dependencies;
  }

  private String checkTcp(String host, int port) {
    try (var socket = new java.net.Socket()) {
      socket.connect(new InetSocketAddress(host, port), (int) PROBE_TIMEOUT.toMillis());
      return "UP";
    } catch (IOException exception) {
      return "DOWN";
    }
  }

  private String checkStorage() {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(properties.storageHealthUrl()))
              .timeout(PROBE_TIMEOUT)
              .GET()
              .build();
      int status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
      return status >= 200 && status < 300 ? "UP" : "DOWN";
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return "DOWN";
    } catch (IOException | IllegalArgumentException exception) {
      return "DOWN";
    }
  }
}
