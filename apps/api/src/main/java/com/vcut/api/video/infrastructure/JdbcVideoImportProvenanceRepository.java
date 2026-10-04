package com.vcut.api.video.infrastructure;

import com.vcut.api.video.application.VideoImportProvenanceRepository;
import com.vcut.api.video.domain.ImportConsent;
import com.vcut.api.video.domain.VideoImportProvenance;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcVideoImportProvenanceRepository implements VideoImportProvenanceRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcVideoImportProvenanceRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void save(UUID videoId, VideoImportProvenance provenance) {
    jdbcTemplate.update(
        "INSERT INTO video_import_provenance (video_id, provider_id, source_origin, "
            + "external_asset_id, consent_policy_version, rights_confirmed, consent_accepted_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
        videoId,
        provenance.providerId(),
        provenance.sourceOrigin(),
        provenance.externalAssetId(),
        provenance.consent().policyVersion(),
        provenance.consent().rightsConfirmed(),
        Timestamp.from(provenance.consentAcceptedAt()));
  }

  @Override
  public Optional<VideoImportProvenance> findByVideoId(UUID videoId) {
    return jdbcTemplate
        .query(
            "SELECT provider_id, source_origin, external_asset_id, consent_policy_version, "
                + "rights_confirmed, consent_accepted_at FROM video_import_provenance "
                + "WHERE video_id = ?",
            JdbcVideoImportProvenanceRepository::map,
            videoId)
        .stream()
        .findFirst();
  }

  private static VideoImportProvenance map(ResultSet resultSet, int rowNumber) throws SQLException {
    return new VideoImportProvenance(
        resultSet.getString("provider_id"),
        URI.create(resultSet.getString("source_origin")),
        resultSet.getString("external_asset_id"),
        new ImportConsent(
            resultSet.getString("consent_policy_version"),
            resultSet.getBoolean("rights_confirmed"),
            resultSet.getTimestamp("consent_accepted_at").toInstant()));
  }
}
