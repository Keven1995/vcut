package com.vcut.api.transcription.presentation;

import com.vcut.api.transcription.domain.Transcription;
import com.vcut.api.transcription.domain.TranscriptionSegment;
import com.vcut.api.transcription.domain.TranscriptionWord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TranscriptionResponse(
    UUID id,
    UUID videoId,
    int pipelineVersion,
    String provider,
    String language,
    String text,
    BigDecimal durationSeconds,
    BigDecimal confidence,
    String status,
    List<SegmentResponse> segments,
    String errorCode,
    String errorMessage,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt) {

  public static TranscriptionResponse from(Transcription transcription) {
    return new TranscriptionResponse(
        transcription.id(),
        transcription.videoId(),
        transcription.pipelineVersion(),
        transcription.provider(),
        transcription.language(),
        transcription.text(),
        transcription.durationSeconds(),
        transcription.confidence(),
        transcription.status().name(),
        transcription.segments().stream().map(SegmentResponse::from).toList(),
        transcription.errorCode(),
        transcription.errorMessage(),
        transcription.createdAt(),
        transcription.updatedAt(),
        transcription.completedAt());
  }

  public record SegmentResponse(
      String text,
      BigDecimal start,
      BigDecimal end,
      BigDecimal confidence,
      List<WordResponse> words) {

    private static SegmentResponse from(TranscriptionSegment segment) {
      return new SegmentResponse(
          segment.text(),
          segment.startSeconds(),
          segment.endSeconds(),
          segment.confidence(),
          segment.words().stream().map(WordResponse::from).toList());
    }
  }

  public record WordResponse(String text, BigDecimal start, BigDecimal end, BigDecimal confidence) {

    private static WordResponse from(TranscriptionWord word) {
      return new WordResponse(
          word.text(), word.startSeconds(), word.endSeconds(), word.confidence());
    }
  }
}
