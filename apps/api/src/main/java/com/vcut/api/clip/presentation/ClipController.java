package com.vcut.api.clip.presentation;

import com.vcut.api.clip.application.ClipApplicationService;
import com.vcut.api.clip.application.ClipEditCommand;
import com.vcut.api.shared.errors.UnauthorizedException;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ClipController {

  private final ClipApplicationService clipApplicationService;

  public ClipController(ClipApplicationService clipApplicationService) {
    this.clipApplicationService = clipApplicationService;
  }

  @PostMapping("/videos/{videoId}/clips")
  public ResponseEntity<ClipResponse> create(
      Principal authentication,
      @PathVariable UUID videoId,
      @Valid @RequestBody CreateClipRequest request) {
    return ResponseEntity.accepted()
        .body(
            ClipResponse.from(
                clipApplicationService.create(
                    userId(authentication),
                    videoId,
                    request.candidateId(),
                    request.aspectRatio(),
                    request.captionPreset())));
  }

  @GetMapping("/videos/{videoId}/clips")
  public ClipPageResponse list(
      Principal authentication,
      @PathVariable UUID videoId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String status) {
    return ClipPageResponse.from(
        clipApplicationService.list(userId(authentication), videoId, page, size, status));
  }

  @GetMapping("/clips/{clipId}")
  public ClipResponse get(Principal authentication, @PathVariable UUID clipId) {
    return ClipResponse.from(clipApplicationService.get(userId(authentication), clipId));
  }

  @PatchMapping("/clips/{clipId}")
  public ClipResponse update(
      Principal authentication, @PathVariable UUID clipId, @RequestBody UpdateClipRequest request) {
    return ClipResponse.from(
        clipApplicationService.update(
            userId(authentication),
            clipId,
            new ClipEditCommand(
                request.startSeconds(),
                request.endSeconds(),
                request.aspectRatio(),
                request.captionPreset(),
                request.captionText(),
                request.fontFamily(),
                request.fontSize(),
                request.fontWeight(),
                request.textColor(),
                request.backgroundColor(),
                request.backgroundOpacity(),
                request.position(),
                request.animation())));
  }

  @PostMapping("/clips/{clipId}/generate")
  public ResponseEntity<ClipResponse> generate(
      Principal authentication, @PathVariable UUID clipId) {
    return ResponseEntity.accepted()
        .body(ClipResponse.from(clipApplicationService.generate(userId(authentication), clipId)));
  }

  @GetMapping("/clips/{clipId}/preview-url")
  public PreviewUrlResponse previewUrl(Principal authentication, @PathVariable UUID clipId) {
    var result = clipApplicationService.previewUrl(userId(authentication), clipId);
    return new PreviewUrlResponse(result.url(), result.expiresAt());
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
