package com.vcut.api.transcription.infrastructure;

import com.vcut.api.transcription.application.TranscriptionRepository;
import com.vcut.api.transcription.domain.Transcription;
import com.vcut.api.transcription.domain.TranscriptionSegment;
import com.vcut.api.transcription.domain.TranscriptionStatus;
import com.vcut.api.transcription.domain.TranscriptionWord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTranscriptionRepository implements TranscriptionRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcTranscriptionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public int nextVersion(UUID videoId) {
    Integer version =
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(pipeline_version), 0) + 1 FROM transcriptions WHERE video_id = ?",
            Integer.class,
            videoId);
    return version == null ? 1 : version;
  }

  @Override
  public Transcription save(Transcription transcription) {
    jdbcTemplate.update(
        "INSERT INTO transcriptions (id, video_id, user_id, pipeline_version, provider, language, transcript_text, "
            + "duration_seconds, confidence, status, error_code, error_message, created_at, updated_at, completed_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        transcription.id(),
        transcription.videoId(),
        transcription.userId(),
        transcription.pipelineVersion(),
        transcription.provider(),
        transcription.language(),
        transcription.text(),
        transcription.durationSeconds(),
        transcription.confidence(),
        transcription.status().name(),
        transcription.errorCode(),
        transcription.errorMessage(),
        Timestamp.from(transcription.createdAt()),
        Timestamp.from(transcription.updatedAt()),
        timestamp(transcription.completedAt()));
    return transcription;
  }

  @Override
  public Optional<Transcription> findLatestForUser(UUID videoId, UUID userId) {
    return jdbcTemplate
        .query(
            "SELECT * FROM transcriptions WHERE video_id = ? AND user_id = ? "
                + "ORDER BY pipeline_version DESC LIMIT 1",
            (resultSet, rowNumber) -> map(resultSet),
            videoId,
            userId)
        .stream()
        .map(this::withSegments)
        .findFirst();
  }

  @Override
  public Optional<Transcription> findByVideoAndVersion(UUID videoId, int pipelineVersion) {
    return jdbcTemplate
        .query(
            "SELECT * FROM transcriptions WHERE video_id = ? AND pipeline_version = ?",
            (resultSet, rowNumber) -> map(resultSet),
            videoId,
            pipelineVersion)
        .stream()
        .map(this::withSegments)
        .findFirst();
  }

  @Override
  public void updateStatus(
      UUID videoId,
      int pipelineVersion,
      TranscriptionStatus status,
      String errorCode,
      String errorMessage,
      Instant now) {
    jdbcTemplate.update(
        "UPDATE transcriptions SET status = ?, error_code = ?, error_message = ?, updated_at = ? "
            + "WHERE video_id = ? AND pipeline_version = ? AND status <> 'COMPLETED'",
        status.name(),
        errorCode,
        errorMessage,
        Timestamp.from(now),
        videoId,
        pipelineVersion);
  }

  @Override
  public void complete(Transcription transcription) {
    jdbcTemplate.update(
        "UPDATE transcriptions SET provider = ?, language = ?, transcript_text = ?, duration_seconds = ?, "
            + "confidence = ?, status = ?, error_code = ?, error_message = ?, updated_at = ?, completed_at = ? "
            + "WHERE video_id = ? AND pipeline_version = ?",
        transcription.provider(),
        transcription.language(),
        transcription.text(),
        transcription.durationSeconds(),
        transcription.confidence(),
        transcription.status().name(),
        transcription.errorCode(),
        transcription.errorMessage(),
        Timestamp.from(transcription.updatedAt()),
        timestamp(transcription.completedAt()),
        transcription.videoId(),
        transcription.pipelineVersion());
    Optional<UUID> transcriptionId =
        jdbcTemplate
            .query(
                "SELECT id FROM transcriptions WHERE video_id = ? AND pipeline_version = ?",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                transcription.videoId(),
                transcription.pipelineVersion())
            .stream()
            .findFirst();
    if (transcriptionId.isEmpty()) {
      throw new IllegalStateException("Transcription row was not found after completion");
    }
    jdbcTemplate.update(
        "DELETE FROM transcription_segments WHERE transcription_id = ?", transcriptionId.get());
    for (int segmentPosition = 0;
        segmentPosition < transcription.segments().size();
        segmentPosition++) {
      TranscriptionSegment segment = transcription.segments().get(segmentPosition);
      UUID segmentId = UUID.randomUUID();
      jdbcTemplate.update(
          "INSERT INTO transcription_segments (id, transcription_id, position, segment_text, start_seconds, end_seconds, confidence) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?)",
          segmentId,
          transcriptionId.get(),
          segmentPosition,
          segment.text(),
          segment.startSeconds(),
          segment.endSeconds(),
          segment.confidence());
      for (int wordPosition = 0; wordPosition < segment.words().size(); wordPosition++) {
        TranscriptionWord word = segment.words().get(wordPosition);
        jdbcTemplate.update(
            "INSERT INTO transcription_words (id, segment_id, position, word_text, start_seconds, end_seconds, confidence) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(),
            segmentId,
            wordPosition,
            word.text(),
            word.startSeconds(),
            word.endSeconds(),
            word.confidence());
      }
    }
  }

  private Transcription withSegments(Transcription transcription) {
    List<TranscriptionSegment> segments =
        jdbcTemplate.query(
            "SELECT * FROM transcription_segments WHERE transcription_id = ? ORDER BY position",
            (resultSet, rowNumber) -> mapSegment(resultSet),
            transcription.id());
    List<TranscriptionSegment> withWords = new ArrayList<>(segments.size());
    for (int segmentPosition = 0; segmentPosition < segments.size(); segmentPosition++) {
      TranscriptionSegment segment = segments.get(segmentPosition);
      UUID segmentId =
          jdbcTemplate.queryForObject(
              "SELECT id FROM transcription_segments WHERE transcription_id = ? AND position = ?",
              UUID.class,
              transcription.id(),
              segmentPosition);
      List<TranscriptionWord> words =
          jdbcTemplate.query(
              "SELECT * FROM transcription_words WHERE segment_id = ? ORDER BY position",
              (resultSet, rowNumber) -> mapWord(resultSet),
              segmentId);
      withWords.add(
          new TranscriptionSegment(
              segment.text(),
              segment.startSeconds(),
              segment.endSeconds(),
              segment.confidence(),
              words));
    }
    return new Transcription(
        transcription.id(),
        transcription.videoId(),
        transcription.userId(),
        transcription.pipelineVersion(),
        transcription.provider(),
        transcription.language(),
        transcription.text(),
        transcription.durationSeconds(),
        transcription.confidence(),
        transcription.status(),
        withWords,
        transcription.errorCode(),
        transcription.errorMessage(),
        transcription.createdAt(),
        transcription.updatedAt(),
        transcription.completedAt());
  }

  private static Transcription map(ResultSet resultSet) throws SQLException {
    return new Transcription(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("video_id", UUID.class),
        resultSet.getObject("user_id", UUID.class),
        resultSet.getInt("pipeline_version"),
        resultSet.getString("provider"),
        resultSet.getString("language"),
        resultSet.getString("transcript_text"),
        resultSet.getBigDecimal("duration_seconds"),
        resultSet.getBigDecimal("confidence"),
        TranscriptionStatus.valueOf(resultSet.getString("status")),
        List.of(),
        resultSet.getString("error_code"),
        resultSet.getString("error_message"),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant(),
        nullableInstant(resultSet, "completed_at"));
  }

  private static TranscriptionSegment mapSegment(ResultSet resultSet) throws SQLException {
    return new TranscriptionSegment(
        resultSet.getString("segment_text"),
        resultSet.getBigDecimal("start_seconds"),
        resultSet.getBigDecimal("end_seconds"),
        resultSet.getBigDecimal("confidence"),
        List.of());
  }

  private static TranscriptionWord mapWord(ResultSet resultSet) throws SQLException {
    return new TranscriptionWord(
        resultSet.getString("word_text"),
        resultSet.getBigDecimal("start_seconds"),
        resultSet.getBigDecimal("end_seconds"),
        resultSet.getBigDecimal("confidence"));
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
    Timestamp timestamp = resultSet.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }
}
