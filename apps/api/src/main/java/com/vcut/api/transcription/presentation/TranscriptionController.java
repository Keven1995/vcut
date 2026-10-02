package com.vcut.api.transcription.presentation;

import com.vcut.api.shared.errors.UnauthorizedException;
import com.vcut.api.transcription.application.TranscriptionApplicationService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnBean(TranscriptionApplicationService.class)
@RequestMapping("/api/videos/{videoId}/transcription")
public class TranscriptionController {

  private final TranscriptionApplicationService transcriptionApplicationService;

  public TranscriptionController(TranscriptionApplicationService transcriptionApplicationService) {
    this.transcriptionApplicationService = transcriptionApplicationService;
  }

  @PostMapping
  public ResponseEntity<TranscriptionResponse> request(
      Principal authentication,
      @PathVariable UUID videoId,
      @Valid @RequestBody(required = false) RequestTranscriptionRequest request) {
    TranscriptionResponse response =
        TranscriptionResponse.from(
            transcriptionApplicationService.request(
                userId(authentication), videoId, request == null ? null : request.language()));
    return ResponseEntity.accepted().body(response);
  }

  @GetMapping
  public TranscriptionResponse get(Principal authentication, @PathVariable UUID videoId) {
    return TranscriptionResponse.from(
        transcriptionApplicationService.get(userId(authentication), videoId));
  }

  @GetMapping("/audio-url")
  public AudioUrlResponse audioUrl(Principal authentication, @PathVariable UUID videoId) {
    return AudioUrlResponse.from(
        transcriptionApplicationService.audioUrl(userId(authentication), videoId));
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
