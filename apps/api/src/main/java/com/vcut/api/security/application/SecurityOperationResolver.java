package com.vcut.api.security.application;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class SecurityOperationResolver {

  private static final Map<String, String> OPERATIONS =
      Map.ofEntries(
          Map.entry("POST /api/auth/login", "auth-login"),
          Map.entry("POST /api/auth/register", "auth-register"),
          Map.entry("POST /api/auth/refresh", "auth-refresh"),
          Map.entry("DELETE /api/auth/logout", "auth-logout"),
          Map.entry("DELETE /api/account", "account-delete"),
          Map.entry("POST /api/projects/{projectId}/videos", "video-upload"),
          Map.entry("POST /api/videos/{videoId}/confirm", "video-upload"),
          Map.entry("POST /api/projects/{projectId}/videos/import", "video-import"),
          Map.entry("POST /api/video-imports/{importId}/cancel", "import-cancel"),
          Map.entry("DELETE /api/videos/{videoId}", "video-delete"),
          Map.entry("DELETE /api/projects/{projectId}", "project-delete"),
          Map.entry("POST /api/videos/{videoId}/process", "video-process"),
          Map.entry("POST /api/videos/{videoId}/transcription", "transcription-request"),
          Map.entry("GET /api/videos/{videoId}/transcription/audio-url", "download-url"),
          Map.entry("POST /api/videos/{videoId}/clip-analysis", "clip-analysis-request"),
          Map.entry("POST /api/videos/{videoId}/clips", "clip-create"),
          Map.entry("POST /api/clips/{clipId}/generate", "clip-generate"),
          Map.entry("GET /api/clips/{clipId}/preview-url", "download-url"),
          Map.entry("POST /api/clips/{clipId}/renders", "render-request"),
          Map.entry("POST /api/renders/{renderId}/retry", "render-retry"),
          Map.entry("GET /api/renders/{renderId}/download-url", "download-url"),
          Map.entry("GET /api/renders/{renderId}/thumbnail-url", "download-url"),
          Map.entry(
              "POST /api/clips/{clipId}/publication-metadata/{platform}/generate",
              "publication-generate"),
          Map.entry("POST /api/subscriptions/checkout", "subscription-checkout"),
          Map.entry("POST /api/subscriptions/{subscriptionId}/cancel", "subscription-cancel"),
          Map.entry(
              "POST /api/subscriptions/checkouts/{checkoutId}/sandbox-confirm",
              "subscription-checkout"),
          Map.entry("POST /api/payments/sandbox/webhook", "payment-webhook"));

  public Optional<String> resolve(String method, String routePattern) {
    if (method == null || routePattern == null || routePattern.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        OPERATIONS.get(method.toUpperCase(java.util.Locale.ROOT) + " " + routePattern));
  }

  public Set<String> operationNames() {
    return Set.copyOf(OPERATIONS.values());
  }
}
