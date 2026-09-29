package com.vcut.api.video.presentation;

import com.vcut.api.shared.errors.UnauthorizedException;
import com.vcut.api.video.application.VideoApplicationService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnBean(VideoApplicationService.class)
@RequestMapping("/api")
public class VideoController {

  private final VideoApplicationService videoApplicationService;

  public VideoController(VideoApplicationService videoApplicationService) {
    this.videoApplicationService = videoApplicationService;
  }

  @PostMapping("/projects/{projectId}/videos")
  public ResponseEntity<VideoResponse> createUploadIntent(
      Principal authentication,
      @PathVariable("projectId") UUID projectId,
      @Valid @RequestBody CreateVideoUploadRequest request) {
    var intent =
        videoApplicationService.createUploadIntent(
            userId(authentication),
            projectId,
            request.filename(),
            request.contentType(),
            request.sizeBytes());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(VideoResponse.from(intent.video(), intent.upload()));
  }

  @PostMapping("/videos/{videoId}/confirm")
  public VideoResponse confirmUpload(
      Principal authentication,
      @PathVariable("videoId") UUID videoId,
      @Valid @RequestBody(required = false) ConfirmVideoUploadRequest request) {
    String checksum = request == null ? null : request.checksumSha256();
    return VideoResponse.from(
        videoApplicationService.confirmUpload(userId(authentication), videoId, checksum));
  }

  @GetMapping("/videos/{videoId}")
  public VideoResponse get(Principal authentication, @PathVariable("videoId") UUID videoId) {
    return VideoResponse.from(videoApplicationService.get(userId(authentication), videoId));
  }

  @DeleteMapping("/videos/{videoId}")
  public ResponseEntity<Void> delete(
      Principal authentication, @PathVariable("videoId") UUID videoId) {
    videoApplicationService.delete(userId(authentication), videoId);
    return ResponseEntity.noContent().build();
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
