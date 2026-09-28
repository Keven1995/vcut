package com.vcut.api.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MigrationPolicyTest {

  private static final Pattern VERSIONED_MIGRATION = Pattern.compile("V\\d+__[a-z0-9_]+\\.sql");

  @Test
  void migrationsAreVersionedAndDoNotUseSequentialPublicIds() throws IOException {
    Path migrations = migrationPath();

    try (var files = Files.list(migrations)) {
      files
          .filter(Files::isRegularFile)
          .forEach(
              file -> {
                assertThat(VERSIONED_MIGRATION.matcher(file.getFileName().toString()).matches())
                    .as("migration filename")
                    .isTrue();
                try {
                  String sql = Files.readString(file);
                  assertThat(sql.toUpperCase())
                      .doesNotContain(" SERIAL ", " BIGSERIAL ", " BIGINT GENERATED");
                } catch (IOException exception) {
                  throw new IllegalStateException("Unable to read migration " + file, exception);
                }
              });
    }
  }

  private static Path migrationPath() {
    Path modulePath = Path.of("src", "main", "resources", "db", "migration");
    return Files.isDirectory(modulePath)
        ? modulePath
        : Path.of("apps", "api", "src", "main", "resources", "db", "migration");
  }
}
