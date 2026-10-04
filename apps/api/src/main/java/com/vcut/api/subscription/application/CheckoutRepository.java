package com.vcut.api.subscription.application;

import com.vcut.api.subscription.domain.CheckoutSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CheckoutRepository {

  CheckoutSession save(CheckoutSession checkoutSession);

  void update(CheckoutSession checkoutSession);

  void expirePendingForUser(UUID userId, java.time.Instant now);

  Optional<CheckoutSession> findCheckoutByIdForUserForUpdate(UUID checkoutId, UUID userId);

  Optional<CheckoutSession> findByProviderSessionIdForUpdate(String providerSessionId);

  Optional<CheckoutSession> findPendingForUser(UUID userId, Instant now);

  List<CheckoutSession> recentForUser(UUID userId, int limit);
}
