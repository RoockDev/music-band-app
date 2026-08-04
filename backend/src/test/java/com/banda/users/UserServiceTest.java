package com.banda.users;

import com.banda.audit.AuditService;
import com.banda.security.Permission;
import com.banda.security.PermissionDeniedException;
import com.banda.security.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Section 3 (User/Musician Management): minor accounts require guardian contact + consent
 * enforced at creation/edit; deactivation blocks login (status flip + tokenVersion bump,
 * mirroring {@code AuthService}'s established defensive pattern) while retaining history
 * (never deleted). {@link PermissionService#requirePermission} and
 * {@link AuditService#record} are called from THIS service layer — PermissionService's own
 * Javadoc names PR 5 as the first real consumer expected to do exactly this.
 */
class UserServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration ACTIVATION_TTL = Duration.ofDays(1);

    private UserAccountRepository userAccountRepository;
    private PasswordTokenRepository passwordTokenRepository;
    private PermissionService permissionService;
    private AuditService auditService;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userAccountRepository = mock(UserAccountRepository.class);
        passwordTokenRepository = mock(PasswordTokenRepository.class);
        permissionService = mock(PermissionService.class);
        auditService = mock(AuditService.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        userService = new UserService(userAccountRepository, passwordTokenRepository,
                permissionService, auditService, clock, ACTIVATION_TTL);

        when(userAccountRepository.saveAndFlush(any(UserAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private UserAccount adminActor() {
        return new UserAccount("admin@example.com", UserRole.ADMIN, UserStatus.ACTIVE, NOW);
    }

    @Test
    void createChecksTheManageUsersPermissionBeforeDoingAnythingElse() {
        UserAccount actor = adminActor();
        doThrow(new PermissionDeniedException(Permission.MANAGE_USERS))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_USERS);

        assertThatThrownBy(() -> userService.create(actor, "new@example.com", UserRole.MUSICIAN, false, null, false))
                .isInstanceOf(PermissionDeniedException.class);

        verifyNoInteractions(userAccountRepository);
        verifyNoInteractions(auditService);
    }

    @Test
    void createPersistsANonMinorAccountAsPendingIssuesAnActivationTokenAndWritesAnAuditRecord() {
        UserAccount actor = adminActor();

        UserService.CreateUserResult result = userService.create(
                actor, "musician@example.com", UserRole.MUSICIAN, false, null, false);

        ArgumentCaptor<UserAccount> savedCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).saveAndFlush(savedCaptor.capture());
        UserAccount saved = savedCaptor.getValue();
        assertThat(saved.getEmail()).isEqualTo("musician@example.com");
        assertThat(saved.getRole()).isEqualTo(UserRole.MUSICIAN);
        assertThat(saved.getStatus()).isEqualTo(UserStatus.PENDING);
        assertThat(saved.isMinor()).isFalse();
        assertThat(saved.getGuardianContact()).isNull();
        assertThat(saved.isConsentOnFile()).isFalse();

        ArgumentCaptor<PasswordToken> tokenCaptor = ArgumentCaptor.forClass(PasswordToken.class);
        verify(passwordTokenRepository).saveAndFlush(tokenCaptor.capture());
        PasswordToken issuedToken = tokenCaptor.getValue();
        assertThat(issuedToken.getType()).isEqualTo(PasswordTokenType.ACTIVATION);
        assertThat(issuedToken.getExpiresAt()).isEqualTo(NOW.plus(ACTIVATION_TTL));
        assertThat(result.activationToken()).isNotBlank();
        // The persisted token stores only the hash — never the raw value.
        assertThat(issuedToken.getTokenHash()).isNotEqualTo(result.activationToken());

        verify(auditService).record(eq(actor.getId()), eq("USER_CREATED"), eq("UserAccount"), any(), anyString());
    }

    @Test
    void createMinorWithoutGuardianContactIsRejectedAndNothingIsPersisted() {
        UserAccount actor = adminActor();

        assertThatThrownBy(() -> userService.create(actor, "minor@example.com", UserRole.MUSICIAN, true, "  ", true))
                .isInstanceOf(InvalidUserDataException.class);

        verifyNoInteractions(userAccountRepository);
        verifyNoInteractions(auditService);
    }

    @Test
    void createMinorWithoutConsentOnFileIsRejectedAndNothingIsPersisted() {
        UserAccount actor = adminActor();

        assertThatThrownBy(() -> userService.create(
                actor, "minor2@example.com", UserRole.MUSICIAN, true, "Parent Name +54911...", false))
                .isInstanceOf(InvalidUserDataException.class);

        verifyNoInteractions(userAccountRepository);
    }

    @Test
    void createMinorWithGuardianContactAndConsentPersistsTheGuardianFields() {
        UserAccount actor = adminActor();

        userService.create(actor, "minor3@example.com", UserRole.MUSICIAN, true, "Parent Name +54911...", true);

        ArgumentCaptor<UserAccount> savedCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).saveAndFlush(savedCaptor.capture());
        UserAccount saved = savedCaptor.getValue();
        assertThat(saved.isMinor()).isTrue();
        assertThat(saved.getGuardianContact()).isEqualTo("Parent Name +54911...");
        assertThat(saved.isConsentOnFile()).isTrue();
    }

    @Test
    void createRejectsAnEmailAlreadyHeldByAnotherAccount() {
        UserAccount actor = adminActor();
        when(userAccountRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.create(actor, "taken@example.com", UserRole.MUSICIAN, false, null, false))
                .isInstanceOf(DuplicateEmailException.class);

        verify(userAccountRepository, never()).saveAndFlush(any(UserAccount.class));
    }

    @Test
    void createTranslatesALostUniqueConstraintRaceIntoADuplicateEmailException() {
        UserAccount actor = adminActor();
        when(userAccountRepository.existsByEmail("race@example.com")).thenReturn(false);
        when(userAccountRepository.saveAndFlush(any(UserAccount.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThatThrownBy(() -> userService.create(actor, "race@example.com", UserRole.MUSICIAN, false, null, false))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void deactivateSetsStatusToDeactivatedBumpsTokenVersionRetainsHistoryAndWritesAnAuditRecord() {
        UserAccount actor = adminActor();
        UserAccount existing = new UserAccount("musician4@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        long versionBefore = existing.getTokenVersion();
        when(userAccountRepository.findById(42L)).thenReturn(Optional.of(existing));

        userService.deactivate(actor, 42L);

        assertThat(existing.getStatus()).isEqualTo(UserStatus.DEACTIVATED);
        assertThat(existing.getTokenVersion()).isGreaterThan(versionBefore);
        verify(userAccountRepository).saveAndFlush(existing);
        verify(auditService).record(eq(actor.getId()), eq("USER_DEACTIVATED"), eq("UserAccount"), eq(42L), any());
    }

    @Test
    void deactivateOnAnUnknownUserThrowsUserNotFoundException() {
        UserAccount actor = adminActor();
        when(userAccountRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deactivate(actor, 999L))
                .isInstanceOf(UserNotFoundException.class);

        verifyNoInteractions(auditService);
    }

    @Test
    void deactivateChecksTheManageUsersPermission() {
        UserAccount actor = adminActor();
        doThrow(new PermissionDeniedException(Permission.MANAGE_USERS))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_USERS);

        assertThatThrownBy(() -> userService.deactivate(actor, 1L))
                .isInstanceOf(PermissionDeniedException.class);

        verifyNoInteractions(userAccountRepository);
    }

    @Test
    void editUpdatesFieldsAndWritesAnAuditRecord() {
        UserAccount actor = adminActor();
        UserAccount existing = new UserAccount("old@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        when(userAccountRepository.findById(7L)).thenReturn(Optional.of(existing));

        UserAccount edited = userService.edit(actor, 7L, "new@example.com", UserRole.ADMIN, false, null, false);

        assertThat(edited.getEmail()).isEqualTo("new@example.com");
        assertThat(edited.getRole()).isEqualTo(UserRole.ADMIN);
        verify(userAccountRepository).saveAndFlush(existing);
        verify(auditService).record(eq(actor.getId()), eq("USER_UPDATED"), eq("UserAccount"), eq(7L), any());
    }

    @Test
    void editingAnUnchangedEmailIsNotTreatedAsADuplicate() {
        UserAccount actor = adminActor();
        UserAccount existing = new UserAccount("same@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        when(userAccountRepository.findById(8L)).thenReturn(Optional.of(existing));

        assertThatCode(() -> userService.edit(actor, 8L, "same@example.com", UserRole.MUSICIAN, false, null, false))
                .doesNotThrowAnyException();

        verify(userAccountRepository, never()).existsByEmail(anyString());
    }

    @Test
    void editRejectsChangingToAnEmailAlreadyHeldByAnotherAccount() {
        UserAccount actor = adminActor();
        UserAccount existing = new UserAccount("current@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        when(userAccountRepository.findById(9L)).thenReturn(Optional.of(existing));
        when(userAccountRepository.existsByEmail("someoneelses@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.edit(
                actor, 9L, "someoneelses@example.com", UserRole.MUSICIAN, false, null, false))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void editEnforcesTheSameMinorGuardianConsentRuleAsCreate() {
        UserAccount actor = adminActor();
        UserAccount existing = new UserAccount("kid@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        when(userAccountRepository.findById(10L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> userService.edit(actor, 10L, "kid@example.com", UserRole.MUSICIAN, true, null, true))
                .isInstanceOf(InvalidUserDataException.class);
    }

    @Test
    void editOnAnUnknownUserThrowsUserNotFoundException() {
        UserAccount actor = adminActor();
        when(userAccountRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.edit(actor, 404L, "x@example.com", UserRole.MUSICIAN, false, null, false))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void getReturnsTheAccountAfterCheckingThePermission() {
        UserAccount actor = adminActor();
        UserAccount existing = new UserAccount("view@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        when(userAccountRepository.findById(11L)).thenReturn(Optional.of(existing));

        UserAccount result = userService.get(actor, 11L);

        assertThat(result).isSameAs(existing);
        verify(permissionService).requirePermission(actor, Permission.MANAGE_USERS);
    }

    @Test
    void getOnAnUnknownUserThrowsUserNotFoundException() {
        UserAccount actor = adminActor();
        when(userAccountRepository.findById(500L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.get(actor, 500L)).isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void listReturnsAllAccountsAfterCheckingThePermission() {
        UserAccount actor = adminActor();
        UserAccount one = new UserAccount("one@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        UserAccount two = new UserAccount("two@example.com", UserRole.ADMIN, UserStatus.ACTIVE, NOW);
        when(userAccountRepository.findAll()).thenReturn(List.of(one, two));

        List<UserAccount> result = userService.list(actor);

        assertThat(result).containsExactly(one, two);
        verify(permissionService).requirePermission(actor, Permission.MANAGE_USERS);
    }
}
