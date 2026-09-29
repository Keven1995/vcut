package com.vcut.api.video.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VideoTest {

  @Test
  void confirmsUploadingVideoAndPreservesAuditFields() {
    Instant createdAt = Instant.parse("2026-09-28T12:00:00Z");
    Video video =
        Video.uploading(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "users/user/projects/project/source/video/original.mp4",
            "original.mp4",
            "video/mp4",
            100,
            createdAt);

    Video confirmed = video.uploaded(100, "checksum", createdAt.plusSeconds(10));

    assertThat(confirmed.status()).isEqualTo(VideoUploadStatus.UPLOADED);
    assertThat(confirmed.actualSizeBytes()).isEqualTo(100);
    assertThat(confirmed.checksumSha256()).isEqualTo("checksum");
    assertThat(confirmed.createdAt()).isEqualTo(createdAt);
  }

  @Test
  void rejectsConfirmationFromTerminalState() {
    Video video =
        Video.uploading(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "users/user/projects/project/source/video/original.mp4",
                "original.mp4",
                "video/mp4",
                100,
                Instant.now())
            .uploaded(100, null, Instant.now());

    assertThatThrownBy(() -> video.uploaded(100, null, Instant.now()))
        .isInstanceOf(IllegalStateException.class);
  }
}
