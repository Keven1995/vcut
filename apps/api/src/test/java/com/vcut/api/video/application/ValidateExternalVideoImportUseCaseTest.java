package com.vcut.api.video.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vcut.api.project.application.ProjectRepository;
import com.vcut.api.project.domain.Project;
import com.vcut.api.project.domain.ProjectStatus;
import com.vcut.api.shared.errors.ValidationException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ValidateExternalVideoImportUseCaseTest {

  private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

  private final ProjectRepository projects = mock(ProjectRepository.class);
  private final SourceImporter importer = new SourceImporter("test-source", "media.example");
  private ValidateExternalVideoImportUseCase useCase;

  @BeforeEach
  void setUp() {
    when(projects.findByIdForUser(PROJECT_ID, USER_ID))
        .thenReturn(
            Optional.of(
                new Project(PROJECT_ID, USER_ID, "Project", ProjectStatus.ACTIVE, NOW, NOW)));
    useCase =
        new ValidateExternalVideoImportUseCase(
            projects,
            List.of(importer),
            new ExternalVideoImporter.ImportLimits(
                100_000_000, Duration.ofHours(2), Duration.ofMinutes(5)),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void validatesRegisteredProviderAndCapturesConsentAndLimits() {
    var result =
        useCase.validate(USER_ID, PROJECT_ID, request("https://media.example/video.mp4", true));

    assertThat(result.importer()).isSameAs(importer);
    assertThat(result.provenance().providerId()).isEqualTo("test-source");
    assertThat(result.provenance().sourceUri())
        .isEqualTo(URI.create("https://media.example/video.mp4"));
    assertThat(result.provenance().consentAcceptedAt()).isEqualTo(NOW);
    assertThat(result.limits().maxBytes()).isEqualTo(100_000_000);
  }

  @Test
  void rejectsUrlsWithoutAnExplicitlyRegisteredAdapter() {
    assertThatThrownBy(
            () ->
                useCase.validate(
                    USER_ID, PROJECT_ID, request("https://unknown.example/video", true)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("not supported");
  }

  @Test
  void rejectsMissingRightsConsentBeforeSelectingAnAdapter() {
    assertThatThrownBy(
            () ->
                useCase.validate(
                    USER_ID, PROJECT_ID, request("https://media.example/video.mp4", false)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Confirm the right");
  }

  @Test
  void provenanceStoresOnlyTheSourceOriginNotUrlCredentialsOrQueryData() {
    var result =
        useCase.validate(
            USER_ID,
            PROJECT_ID,
            request("https://media.example/private/video.mp4?signature=not-for-storage", true));

    assertThat(result.provenance().sourceOrigin()).isEqualTo("https://media.example");
  }

  @Test
  void rejectsUrlsWithCredentialsOrNonHttpSchemes() {
    for (String url : List.of("https://user:secret@media.example/video", "file:///tmp/video.mp4")) {
      assertThatThrownBy(() -> useCase.validate(USER_ID, PROJECT_ID, request(url, true)))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("URL is invalid");
    }
  }

  private static ValidateExternalVideoImportUseCase.ImportRequest request(
      String url, boolean rightsConfirmed) {
    return new ValidateExternalVideoImportUseCase.ImportRequest(
        UUID.randomUUID(),
        "test-source",
        url,
        "external-123",
        "import-consent-v1",
        rightsConfirmed);
  }

  private static final class SourceImporter implements ExternalVideoImporter {
    private final String providerId;
    private final String host;

    private SourceImporter(String providerId, String host) {
      this.providerId = providerId;
      this.host = host;
    }

    @Override
    public String providerId() {
      return providerId;
    }

    @Override
    public boolean supports(URI sourceUri) {
      return host.equalsIgnoreCase(sourceUri.getHost());
    }

    @Override
    public ImportedMedia importMedia(
        com.vcut.api.video.domain.VideoImportProvenance provenance,
        ImportLimits limits,
        UUID importId,
        CancellationSignal cancellationSignal) {
      throw new UnsupportedOperationException();
    }
  }
}
