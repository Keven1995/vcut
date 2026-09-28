package com.vcut.api.shared.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExternalResourceIdTest {

  @Test
  void generatesUuidBackedExternalIds() {
    ExternalResourceId id = ExternalResourceId.generate();

    assertThat(id.value()).isInstanceOf(UUID.class);
  }
}
