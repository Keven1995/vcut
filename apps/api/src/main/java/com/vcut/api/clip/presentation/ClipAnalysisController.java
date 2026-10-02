package com.vcut.api.clip.presentation;

import com.vcut.api.clip.application.ClipAnalysisApplicationService;
import com.vcut.api.shared.errors.UnauthorizedException;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ClipAnalysisController {

  private final ClipAnalysisApplicationService clipAnalysisApplicationService;

  public ClipAnalysisController(ClipAnalysisApplicationService clipAnalysisApplicationService) {
    this.clipAnalysisApplicationService = clipAnalysisApplicationService;
  }

  @PostMapping("/videos/{videoId}/clip-analysis")
  public ResponseEntity<ClipAnalysisRunResponse> request(
      Principal authentication,
      @PathVariable UUID videoId,
      @Valid @RequestBody RequestClipAnalysisRequest request) {
    var run =
        clipAnalysisApplicationService.request(
            userId(authentication),
            videoId,
            request.durationPreference(),
            request.customDurationSeconds());
    return ResponseEntity.accepted().body(ClipAnalysisRunResponse.from(run));
  }

  @GetMapping({
    "/videos/{videoId}/clip-analysis",
    "/videos/{videoId}/clip-analysis/candidates",
    "/videos/{videoId}/clip-candidates"
  })
  public List<ClipCandidateResponse> list(Principal authentication, @PathVariable UUID videoId) {
    return clipAnalysisApplicationService.list(userId(authentication), videoId).stream()
        .map(ClipCandidateResponse::from)
        .toList();
  }

  @GetMapping("/videos/{videoId}/clip-analysis/run")
  public ClipAnalysisRunResponse latestRun(Principal authentication, @PathVariable UUID videoId) {
    return ClipAnalysisRunResponse.from(
        clipAnalysisApplicationService.latestRun(userId(authentication), videoId));
  }

  @GetMapping({
    "/videos/{videoId}/clip-analysis/candidates/{candidateId}",
    "/videos/{videoId}/clip-candidates/{candidateId}"
  })
  public ClipCandidateResponse get(
      Principal authentication, @PathVariable UUID videoId, @PathVariable UUID candidateId) {
    return ClipCandidateResponse.from(
        clipAnalysisApplicationService.get(userId(authentication), videoId, candidateId));
  }

  @PostMapping("/clip-candidates/{candidateId}/accept")
  public ResponseEntity<Void> accept(Principal authentication, @PathVariable UUID candidateId) {
    clipAnalysisApplicationService.accept(userId(authentication), candidateId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/clip-candidates/{candidateId}/discard")
  public ResponseEntity<Void> discard(Principal authentication, @PathVariable UUID candidateId) {
    clipAnalysisApplicationService.discard(userId(authentication), candidateId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/clip-candidates/{candidateId}/select")
  public ResponseEntity<Void> select(Principal authentication, @PathVariable UUID candidateId) {
    clipAnalysisApplicationService.select(userId(authentication), candidateId);
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
