package com.vcut.api.video.application;

import com.vcut.api.project.application.ProjectRepository;
import com.vcut.api.shared.errors.ResourceNotFoundException;
import com.vcut.api.shared.errors.ValidationException;
import com.vcut.api.video.domain.ImportConsent;
import com.vcut.api.video.domain.VideoImportProvenance;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Validates source, consent, ownership, registered adapter and configured limits. */
@Service
public class ValidateExternalVideoImportUseCase {

  private final ProjectRepository projectRepository;
  private final List<ExternalVideoImporter> importers;
  private final ExternalVideoImporter.ImportLimits limits;
  private final Clock clock;

  @Autowired
  public ValidateExternalVideoImportUseCase(
      ProjectRepository projectRepository,
      List<ExternalVideoImporter> importers,
      ExternalVideoImporter.ImportLimits limits) {
    this(projectRepository, importers, limits, Clock.systemUTC());
  }

  ValidateExternalVideoImportUseCase(
      ProjectRepository projectRepository,
      List<ExternalVideoImporter> importers,
      ExternalVideoImporter.ImportLimits limits,
      Clock clock) {
    this.projectRepository = projectRepository;
    this.importers = List.copyOf(importers);
    this.limits = limits;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public ValidatedImport validate(UUID userId, UUID projectId, ImportRequest request) {
    if (request.importId() == null) {
      throw new ValidationException("importId is required.");
    }
    projectRepository
        .findByIdForUser(projectId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("Project not found."));
    if (!request.rightsConfirmed()) {
      throw new ValidationException("Confirm the right to import and use this media.");
    }

    URI sourceUri = parseSource(request.sourceUrl());
    String providerId = normalizeProviderId(request.providerId());
    ExternalVideoImporter importer =
        importers.stream()
            .filter(candidate -> candidate.providerId().equals(providerId))
            .filter(candidate -> candidate.supports(sourceUri))
            .findFirst()
            .orElseThrow(
                () -> new ValidationException("This external video source is not supported."));

    ImportConsent consent =
        new ImportConsent(request.consentPolicyVersion(), true, clock.instant());
    VideoImportProvenance provenance =
        new VideoImportProvenance(providerId, sourceUri, request.externalAssetId(), consent);
    return new ValidatedImport(userId, projectId, importer, provenance, limits);
  }

  private static URI parseSource(String sourceUrl) {
    if (sourceUrl == null || sourceUrl.isBlank() || sourceUrl.length() > 2_048) {
      throw new ValidationException("External video URL is invalid.");
    }
    try {
      URI uri = new URI(sourceUrl.trim()).normalize();
      String scheme = uri.getScheme();
      if (scheme == null
          || !(scheme.equalsIgnoreCase("https")
              || scheme.equalsIgnoreCase("http")
              || scheme.equalsIgnoreCase("fixture"))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getFragment() != null) {
        throw new ValidationException("External video URL is invalid.");
      }
      return uri;
    } catch (URISyntaxException exception) {
      throw new ValidationException("External video URL is invalid.");
    }
  }

  private static String normalizeProviderId(String providerId) {
    if (providerId == null || !providerId.matches("[a-z0-9][a-z0-9-]{0,63}")) {
      throw new ValidationException("External video provider is invalid.");
    }
    return providerId.toLowerCase(Locale.ROOT);
  }

  public record ImportRequest(
      UUID importId,
      String providerId,
      String sourceUrl,
      String externalAssetId,
      String consentPolicyVersion,
      boolean rightsConfirmed) {}

  public record ValidatedImport(
      UUID userId,
      UUID projectId,
      ExternalVideoImporter importer,
      VideoImportProvenance provenance,
      ExternalVideoImporter.ImportLimits limits) {}
}
