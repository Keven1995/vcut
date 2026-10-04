package com.vcut.api.auth.application;

import com.vcut.api.auth.domain.User;
import com.vcut.api.auth.domain.UserStatus;
import com.vcut.api.shared.errors.ResourceNotFoundException;
import com.vcut.api.shared.errors.ValidationException;
import com.vcut.api.usage.application.RetentionApplicationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountDeletionApplicationService {

  private static final Duration RETURN_PERIOD = Duration.ofDays(30);

  private final UserRepository userRepository;
  private final RefreshSessionRepository refreshSessionRepository;
  private final AccountDeletionRepository deletionRepository;
  private final RetentionApplicationService retentionApplicationService;
  private final Clock clock;

  @Autowired
  public AccountDeletionApplicationService(
      UserRepository userRepository,
      RefreshSessionRepository refreshSessionRepository,
      AccountDeletionRepository deletionRepository,
      RetentionApplicationService retentionApplicationService) {
    this(
        userRepository,
        refreshSessionRepository,
        deletionRepository,
        retentionApplicationService,
        Clock.systemUTC());
  }

  AccountDeletionApplicationService(
      UserRepository userRepository,
      RefreshSessionRepository refreshSessionRepository,
      AccountDeletionRepository deletionRepository,
      RetentionApplicationService retentionApplicationService,
      Clock clock) {
    this.userRepository = userRepository;
    this.refreshSessionRepository = refreshSessionRepository;
    this.deletionRepository = deletionRepository;
    this.retentionApplicationService = retentionApplicationService;
    this.clock = clock;
  }

  @Transactional
  public Instant request(UUID userId) {
    User user =
        userRepository.findById(userId).orElseThrow(AccountDeletionApplicationService::notFound);
    if (user.status() == UserStatus.DELETION_PENDING) {
      return deletionRepository
          .findPendingDeleteAfter(userId)
          .orElseThrow(AccountDeletionApplicationService::notFound);
    }
    if (!user.canAuthenticate()) {
      throw new ValidationException("This account cannot be scheduled for deletion.");
    }

    Instant now = clock.instant();
    Instant deleteAfter = now.plus(RETURN_PERIOD);
    deletionRepository.enqueue(userId, now, deleteAfter);
    userRepository.updateStatus(userId, UserStatus.DELETION_PENDING, now);
    refreshSessionRepository.revokeAllForUser(userId, now);
    retentionApplicationService.extendForAccountDeletion(userId, deleteAfter);
    return deletionRepository
        .findPendingDeleteAfter(userId)
        .orElseThrow(AccountDeletionApplicationService::notFound);
  }

  @Transactional
  public boolean cancelForReturningUser(UUID userId) {
    Instant now = clock.instant();
    if (!deletionRepository.cancelPending(userId, now)) {
      return false;
    }
    userRepository.updateStatus(userId, UserStatus.ACTIVE, now);
    retentionApplicationService.refreshForUser(userId);
    return true;
  }

  private static ResourceNotFoundException notFound() {
    return new ResourceNotFoundException("Account not found.");
  }
}
