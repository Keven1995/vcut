package com.vcut.api.job.presentation;

import com.vcut.api.job.application.JobApplicationService;
import com.vcut.api.shared.errors.UnauthorizedException;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class JobController {

  private final JobApplicationService jobApplicationService;

  public JobController(JobApplicationService jobApplicationService) {
    this.jobApplicationService = jobApplicationService;
  }

  @PostMapping("/videos/{videoId}/process")
  public ResponseEntity<JobResponse> process(
      Principal authentication, @PathVariable("videoId") UUID videoId) {
    JobResponse response =
        JobResponse.from(jobApplicationService.process(userId(authentication), videoId));
    return ResponseEntity.accepted().body(response);
  }

  @GetMapping("/jobs/{jobId}")
  public JobResponse get(Principal authentication, @PathVariable("jobId") UUID jobId) {
    return JobResponse.from(jobApplicationService.get(userId(authentication), jobId));
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
