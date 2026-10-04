package com.vcut.api.subscription.application;

import com.vcut.api.subscription.domain.PaidSubscription;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaidSubscriptionRepository {

  Optional<PaidSubscription> findLatestForUser(UUID userId);

  Optional<PaidSubscription> findByProviderSubscriptionIdForUpdate(String providerSubscriptionId);

  Optional<PaidSubscription> findSubscriptionByIdForUserForUpdate(UUID subscriptionId, UUID userId);

  PaidSubscription save(PaidSubscription subscription);

  void update(PaidSubscription subscription);

  List<PaidSubscription> historyForUser(UUID userId, int limit);
}
