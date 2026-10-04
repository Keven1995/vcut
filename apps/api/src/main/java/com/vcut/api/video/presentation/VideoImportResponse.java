package com.vcut.api.video.presentation;

import com.vcut.api.video.application.ImportExternalVideoUseCase;
import java.time.Instant;

public record VideoImportResponse(VideoResponse video, ImportProvenanceResponse provenance) {

  public static VideoImportResponse from(ImportExternalVideoUseCase.ImportedVideo imported) {
    var provenance = imported.provenance();
    return new VideoImportResponse(
        VideoResponse.from(imported.video()),
        new ImportProvenanceResponse(
            provenance.providerId(),
            provenance.sourceOrigin(),
            provenance.externalAssetId(),
            provenance.consent().policyVersion(),
            provenance.consentAcceptedAt()));
  }

  public record ImportProvenanceResponse(
      String providerId,
      String sourceOrigin,
      String externalAssetId,
      String consentPolicyVersion,
      Instant consentAcceptedAt) {}
}
