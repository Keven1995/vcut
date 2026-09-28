package com.vcut.api.auth.application;

import com.vcut.api.auth.domain.User;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

  Optional<User> findById(UUID id);

  Optional<User> findByNormalizedEmail(String normalizedEmail);

  User save(User user);

  void touch(UUID userId, java.time.Instant updatedAt);
}
