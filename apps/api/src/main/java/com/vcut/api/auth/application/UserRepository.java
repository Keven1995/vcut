package com.vcut.api.auth.application;

import com.vcut.api.auth.domain.User;
import com.vcut.api.auth.domain.UserStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

  Optional<User> findById(UUID id);

  Optional<User> findByNormalizedEmail(String normalizedEmail);

  User save(User user);

  void touch(UUID userId, java.time.Instant updatedAt);

  void updateStatus(UUID userId, UserStatus status, Instant updatedAt);
}
