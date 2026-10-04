package com.vcut.api.clip.presentation;

import com.vcut.api.clip.application.ClipPublicationMetadataService;
import com.vcut.api.clip.domain.PublicationPlatform;
import com.vcut.api.shared.errors.UnauthorizedException;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clips/{clipId}/publication-metadata")
public class ClipPublicationMetadataController {

  private final ClipPublicationMetadataService service;

  public ClipPublicationMetadataController(ClipPublicationMetadataService service) {
    this.service = service;
  }

  @GetMapping
  public List<PublicationMetadataResponse> list(
      Principal authentication, @PathVariable UUID clipId) {
    return service.list(userId(authentication), clipId).stream()
        .map(PublicationMetadataResponse::from)
        .toList();
  }

  @PostMapping("/{platform}/generate")
  public PublicationMetadataResponse generate(
      Principal authentication,
      @PathVariable UUID clipId,
      @PathVariable PublicationPlatform platform) {
    return PublicationMetadataResponse.from(
        service.generate(userId(authentication), clipId, platform));
  }

  @PutMapping("/{platform}")
  public PublicationMetadataResponse edit(
      Principal authentication,
      @PathVariable UUID clipId,
      @PathVariable PublicationPlatform platform,
      @Valid @RequestBody PublicationMetadataRequest request) {
    return PublicationMetadataResponse.from(
        service.edit(
            userId(authentication),
            clipId,
            platform,
            request.title(),
            request.description(),
            request.hashtags()));
  }

  @PostMapping("/{platform}/review")
  public PublicationMetadataResponse review(
      Principal authentication,
      @PathVariable UUID clipId,
      @PathVariable PublicationPlatform platform) {
    return PublicationMetadataResponse.from(
        service.review(userId(authentication), clipId, platform));
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
