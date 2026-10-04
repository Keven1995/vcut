package com.vcut.api.video.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vcut.api.shared.errors.ResourceNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VideoImportCancellationRegistryTest {

  private static final UUID IMPORT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID USER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @Test
  void cancelsOnlyTheActiveImportOwnedByTheRequestingUser() {
    var registry = new VideoImportCancellationRegistry();
    try (var registration = registry.register(IMPORT_ID, USER_ID)) {
      assertThat(registration.isCancelled()).isFalse();
      assertThatThrownBy(() -> registry.cancel(IMPORT_ID, UUID.randomUUID()))
          .isInstanceOf(ResourceNotFoundException.class);

      registry.cancel(IMPORT_ID, USER_ID);

      assertThat(registration.isCancelled()).isTrue();
    }
    assertThatThrownBy(() -> registry.cancel(IMPORT_ID, USER_ID))
        .isInstanceOf(ResourceNotFoundException.class);
  }
}
