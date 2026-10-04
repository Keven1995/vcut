package com.vcut.api.video.presentation;

import com.vcut.api.shared.errors.UnauthorizedException;
import com.vcut.api.video.application.ImportExternalVideoUseCase;
import com.vcut.api.video.application.VideoImportCancellationRegistry;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnBean(ImportExternalVideoUseCase.class)
@ConditionalOnProperty(prefix = "vcut.external-import", name = "enabled", havingValue = "true")
@RequestMapping("/api")
public class VideoImportController {

  private final ImportExternalVideoUseCase importExternalVideo;
  private final VideoImportCancellationRegistry cancellationRegistry;

  public VideoImportController(
      ImportExternalVideoUseCase importExternalVideo,
      VideoImportCancellationRegistry cancellationRegistry) {
    this.importExternalVideo = importExternalVideo;
    this.cancellationRegistry = cancellationRegistry;
  }

  @PostMapping("/projects/{projectId}/videos/import")
  public ResponseEntity<VideoImportResponse> importVideo(
      Principal authentication,
      @PathVariable("projectId") UUID projectId,
      @Valid @RequestBody ImportVideoRequest request) {
    var imported =
        importExternalVideo.execute(
            userId(authentication),
            projectId,
            new com.vcut.api.video.application.ValidateExternalVideoImportUseCase.ImportRequest(
                request.importId(),
                request.providerId(),
                request.sourceUrl(),
                request.externalAssetId(),
                request.consentPolicyVersion(),
                request.rightsConfirmed()));
    return ResponseEntity.status(HttpStatus.CREATED).body(VideoImportResponse.from(imported));
  }

  @PostMapping("/video-imports/{importId}/cancel")
  public ResponseEntity<Void> cancelImport(
      Principal authentication, @PathVariable("importId") UUID importId) {
    cancellationRegistry.cancel(importId, userId(authentication));
    return ResponseEntity.accepted().build();
  }

  private static UUID userId(Principal authentication) {
    if (authentication == null || authentication.getName() == null) {
      throw new UnauthorizedException("Authentication is required.");
    }
    try {
      return UUID.fromString(authentication.getName());
    } catch (IllegalArgumentException exception) {
      throw new UnauthorizedException("Authentication is invalid.");
    }
  }
}
