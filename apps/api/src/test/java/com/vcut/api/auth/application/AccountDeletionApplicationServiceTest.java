package com.vcut.api.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vcut.api.auth.domain.User;
import com.vcut.api.auth.domain.UserStatus;
import com.vcut.api.shared.errors.ValidationException;
import com.vcut.api.usage.application.RetentionApplicationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountDeletionApplicationServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
  private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Test
  void blocksAccountAndQueuesContentCleanupForThirtyDays() {
    UserRepository users = mock(UserRepository.class);
    RefreshSessionRepository sessions = mock(RefreshSessionRepository.class);
    AccountDeletionRepository deletions = mock(AccountDeletionRepository.class);
    RetentionApplicationService retention = mock(RetentionApplicationService.class);
    Instant expectedDeleteAfter = NOW.plusSeconds(30L * 24 * 60 * 60);
    when(users.findById(USER_ID)).thenReturn(Optional.of(user(UserStatus.ACTIVE)));
    when(deletions.findPendingDeleteAfter(USER_ID)).thenReturn(Optional.of(expectedDeleteAfter));
    var service =
        new AccountDeletionApplicationService(
            users, sessions, deletions, retention, Clock.fixed(NOW, ZoneOffset.UTC));

    Instant scheduledFor = service.request(USER_ID);

    assertThat(scheduledFor).isEqualTo(expectedDeleteAfter);
    verify(users).updateStatus(USER_ID, UserStatus.DELETION_PENDING, NOW);
    verify(sessions).revokeAllForUser(USER_ID, NOW);
    verify(deletions).enqueue(USER_ID, NOW, scheduledFor);
    verify(retention).extendForAccountDeletion(USER_ID, scheduledFor);
  }

  @Test
  void refusesDeletionForAlreadyDisabledAccounts() {
    UserRepository users = mock(UserRepository.class);
    when(users.findById(USER_ID)).thenReturn(Optional.of(user(UserStatus.DISABLED)));
    var service =
        new AccountDeletionApplicationService(
            users,
            mock(RefreshSessionRepository.class),
            mock(AccountDeletionRepository.class),
            mock(RetentionApplicationService.class),
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> service.request(USER_ID)).isInstanceOf(ValidationException.class);
  }

  @Test
  void returningUserCancelsOnlyPendingDeletionAndRecalculatesAssetRetention() {
    UserRepository users = mock(UserRepository.class);
    AccountDeletionRepository deletions = mock(AccountDeletionRepository.class);
    RetentionApplicationService retention = mock(RetentionApplicationService.class);
    when(deletions.cancelPending(USER_ID, NOW)).thenReturn(true);
    var service =
        new AccountDeletionApplicationService(
            users,
            mock(RefreshSessionRepository.class),
            deletions,
            retention,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(service.cancelForReturningUser(USER_ID)).isTrue();

    verify(users).updateStatus(USER_ID, UserStatus.ACTIVE, NOW);
    verify(retention).refreshForUser(USER_ID);
  }

  private static User user(UserStatus status) {
    return new User(
        USER_ID, "person@example.com", "person@example.com", "password-hash", status, NOW, NOW);
  }
}
