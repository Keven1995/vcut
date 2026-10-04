package com.vcut.api.usage.application;

import com.vcut.api.usage.domain.Subscription;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository {

  Subscription save(Subscription subscription);

  Optional<Subscription> findActiveForUser(UUID userId, Instant at);
}
