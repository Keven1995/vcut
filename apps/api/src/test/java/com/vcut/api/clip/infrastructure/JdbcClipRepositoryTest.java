package com.vcut.api.clip.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.vcut.api.clip.domain.AspectRatio;
import com.vcut.api.clip.domain.CaptionPreset;
import com.vcut.api.clip.domain.Clip;
import com.vcut.api.clip.domain.ClipScore;
import com.vcut.api.clip.domain.ClipVersion;
import com.vcut.api.clip.domain.CropSettings;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcClipRepositoryTest {

  @Test
  void insertBindsOneValueForEveryClipColumn() {
    CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
    JdbcClipRepository repository = new JdbcClipRepository(jdbcTemplate);
    Instant now = Instant.parse("2026-10-04T12:00:00Z");
    Clip clip = clip(now);

    repository.save(clip);

    assertThat(jdbcTemplate.sql).contains("INSERT INTO clips");
    assertThat(placeholderCount(jdbcTemplate.sql)).isEqualTo(jdbcTemplate.arguments.length);
    assertThat(jdbcTemplate.arguments).hasSize(24);
  }

  @Test
  void versionInsertBindsOneValueForEveryVersionColumn() {
    CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
    JdbcClipRepository repository = new JdbcClipRepository(jdbcTemplate);
    Instant now = Instant.parse("2026-10-04T12:00:00Z");
    Clip clip = clip(now);
    ClipVersion version =
        ClipVersion.initial(
            UUID.randomUUID(),
            clip,
            BigDecimal.ZERO,
            BigDecimal.valueOf(30),
            AspectRatio.PORTRAIT,
            CropSettings.centered(),
            CaptionPreset.MINIMAL,
            CaptionPreset.MINIMAL.defaultStyle(),
            List.of(),
            now);

    repository.saveVersion(version);

    assertThat(jdbcTemplate.sql).contains("INSERT INTO clip_versions");
    assertThat(placeholderCount(jdbcTemplate.sql)).isEqualTo(jdbcTemplate.arguments.length);
    assertThat(jdbcTemplate.arguments).hasSize(23);
  }

  private static Clip clip(Instant now) {
    return Clip.created(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        new ClipScore(
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO),
        now);
  }

  private static long placeholderCount(String sql) {
    return sql.chars().filter(character -> character == '?').count();
  }

  private static final class CapturingJdbcTemplate extends JdbcTemplate {

    private String sql;
    private Object[] arguments;

    @Override
    public int update(String sql, Object... arguments) {
      this.sql = sql;
      this.arguments = arguments;
      return 1;
    }
  }
}
