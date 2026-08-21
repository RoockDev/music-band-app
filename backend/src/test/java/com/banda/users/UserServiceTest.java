package com.banda.users;

import com.banda.audit.AuditService;
import com.banda.events.EventMusicianAccessRepository;
import com.banda.groups.MusicianGroupRepository;
import com.banda.security.AdminPermissionRepository;
import com.banda.security.Permission;
import com.banda.security.PermissionDeniedException;
import com.banda.security.PermissionService;
import com.banda.sheetmusic.SheetMusicianAccessRepository;
import com.banda.users.dto.CreateUserRequest;
import com.banda.users.dto.UpdateUserRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

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
 *
 * <p>Also covers the post-review privilege-escalation hardening: {@link Permission#MANAGE_USERS}
 * alone must never be enough to mint or promote an ADMIN — that additionally requires
 * {@link Permission#MANAGE_ADMIN_ROLES} — and no actor may {@link UserService#edit}/
 * {@link UserService#deactivate} their own account, regardless of permissions held.
 */
class UserServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration ACTIVATION_TTL = Duration.ofDays(1);

    private UserAccountRepository userAccountRepository;
    private PasswordTokenRepository passwordTokenRepository;
    private AdminPermissionRepository adminPermissionRepository;
    private MusicianGroupRepository musicianGroupRepository;
    private EventMusicianAccessRepository eventMusicianAccessRepository;
    private SheetMusicianAccessRepository sheetMusicianAccessRepository;
    private PermissionService permissionService;
    private AuditService auditService;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userAccountRepository = mock(UserAccountRepository.class);
        passwordTokenRepository = mock(PasswordTokenRepository.class);
        adminPermissionRepository = mock(AdminPermissionRepository.class);
        musicianGroupRepository = mock(MusicianGroupRepository.class);
        eventMusicianAccessRepository = mock(EventMusicianAccessRepository.class);
        sheetMusicianAccessRepository = mock(SheetMusicianAccessRepository.class);
        permissionService = mock(PermissionService.class);
        auditService = mock(AuditService.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        userService = new UserService(userAccountRepository, passwordTokenRepository, adminPermissionRepository,
                musicianGroupRepository, eventMusicianAccessRepository, sheetMusicianAccessRepository,
                permissionService, auditService, clock, ACTIVATION_TTL);

        when(userAccountRepository.saveAndFlush(any(UserAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private UserAccount adminActor() {
        UserAccount actor = new UserAccount("admin@example.com", UserRole.ADMIN, UserStatus.ACTIVE, NOW);
        ReflectionTestUtils.setField(actor, "id", 1L);
        ReflectionTestUtils.setField(actor, "version", 0L);
        return actor;
    }

    private static CreateUserRequest createRequest(String email, UserRole role) {
        return new CreateUserRequest(email, role, false, null, false);
    }

    private static UpdateUserRequest updateRequest(String email, UserRole role) {
        return new UpdateUserRequest(email, role, false, null, false, 0L);
    }

    private void prepareMutation(UserAccount actor, Long id, UserAccount target) {
        ReflectionTestUtils.setField(target, "id", id);
        ReflectionTestUtils.setField(target, "version", 0L);
        when(userAccountRepository.findAllByIdForPermissionMutation(List.of(actor.getId(), id).stream().sorted().toList()))
                .thenReturn(List.of(actor, target).stream().sorted((left, right) -> left.getId().compareTo(right.getId())).toList());
    }

    private void prepareMissingMutation(UserAccount actor, Long id) {
        when(userAccountRepository.findAllByIdForPermissionMutation(List.of(actor.getId(), id).stream().sorted().toList()))
                .thenReturn(List.of(actor));
    }

    // ---- create() ----

    @Test
    void createChecksTheManageUsersPermissionBeforeDoingAnythingElse() {
        UserAccount actor = adminActor();
        doThrow(new PermissionDeniedException(Permission.MANAGE_USERS))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_USERS);

        assertThatThrownBy(() -> userService.create(actor, createRequest("new@example.com", UserRole.MUSICIAN)))
                .isInstanceOf(PermissionDeniedException.class);

        verifyNoInteractions(userAccountRepository);
        verifyNoInteractions(auditService);
    }

    @Test
    void createPersistsANonMinorAccountAsPendingIssuesAnActivationTokenAndWritesAnAuditRecord() {
        UserAccount actor = adminActor();

        UserService.CreateUserResult result = userService.create(
                actor, createRequest("musician@example.com", UserRole.MUSICIAN));

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

        assertThatThrownBy(() -> userService.create(actor,
                new CreateUserRequest("minor@example.com", UserRole.MUSICIAN, true, "  ", true)))
                .isInstanceOf(InvalidUserDataException.class);

        verifyNoInteractions(userAccountRepository);
        verifyNoInteractions(auditService);
    }

    @Test
    void createMinorWithoutConsentOnFileIsRejectedAndNothingIsPersisted() {
        UserAccount actor = adminActor();

        assertThatThrownBy(() -> userService.create(actor,
                new CreateUserRequest("minor2@example.com", UserRole.MUSICIAN, true, "Parent Name +54911...", false)))
                .isInstanceOf(InvalidUserDataException.class);

        verifyNoInteractions(userAccountRepository);
    }

    @Test
    void createMinorWithGuardianContactAndConsentPersistsTheGuardianFields() {
        UserAccount actor = adminActor();

        userService.create(actor,
                new CreateUserRequest("minor3@example.com", UserRole.MUSICIAN, true, "Parent Name +54911...", true));

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

        assertThatThrownBy(() -> userService.create(actor, createRequest("taken@example.com", UserRole.MUSICIAN)))
                .isInstanceOf(DuplicateEmailException.class);

        verify(userAccountRepository, never()).saveAndFlush(any(UserAccount.class));
    }

    @Test
    void createTranslatesALostUniqueConstraintRaceIntoADuplicateEmailException() {
        UserAccount actor = adminActor();
        when(userAccountRepository.existsByEmail("race@example.com")).thenReturn(false);
        when(userAccountRepository.saveAndFlush(any(UserAccount.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThatThrownBy(() -> userService.create(actor, createRequest("race@example.com", UserRole.MUSICIAN)))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void createOfAnAdminRoleWithoutManageAdminRolesPermissionIsDenied() {
        UserAccount actor = adminActor();
        doThrow(new PermissionDeniedException(Permission.MANAGE_ADMIN_ROLES))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_ADMIN_ROLES);

        assertThatThrownBy(() -> userService.create(actor, createRequest("new-admin@example.com", UserRole.ADMIN)))
                .isInstanceOf(PermissionDeniedException.class);

        verifyNoInteractions(userAccountRepository);
        verifyNoInteractions(auditService);
    }

    @Test
    void createOfAMusicianRoleDoesNotRequireManageAdminRolesPermission() {
        UserAccount actor = adminActor();

        userService.create(actor, createRequest("plain-musician@example.com", UserRole.MUSICIAN));

        verify(permissionService, never()).requirePermission(actor, Permission.MANAGE_ADMIN_ROLES);
    }

    @Test
    void createOfAnAdminRoleWithBothPermissionsSucceeds() {
        UserAccount actor = adminActor();

        UserService.CreateUserResult result = userService.create(
                actor, createRequest("new-admin2@example.com", UserRole.ADMIN));

        assertThat(result.user().getRole()).isEqualTo(UserRole.ADMIN);
        verify(permissionService).requirePermission(actor, Permission.MANAGE_USERS);
        verify(permissionService).requirePermission(actor, Permission.MANAGE_ADMIN_ROLES);
    }

    // ---- deactivate() ----

    @Test
    void deactivateSetsStatusToDeactivatedBumpsTokenVersionRetainsHistoryAndWritesAnAuditRecord() {
        UserAccount actor = adminActor();
        UserAccount existing = new UserAccount("musician4@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        long versionBefore = existing.getTokenVersion();
        prepareMutation(actor, 42L, existing);

        userService.deactivate(actor, 42L);

        assertThat(existing.getStatus()).isEqualTo(UserStatus.DEACTIVATED);
        assertThat(existing.getTokenVersion()).isGreaterThan(versionBefore);
        verify(userAccountRepository).saveAndFlush(existing);
        verify(auditService).record(eq(actor.getId()), eq("USER_DEACTIVATED"), eq("UserAccount"), eq(42L));
    }

    @Test
    void deactivateOnAnUnknownUserThrowsUserNotFoundException() {
        UserAccount actor = adminActor();
        prepareMissingMutation(actor, 999L);

        assertThatThrownBy(() -> userService.deactivate(actor, 999L))
                .isInstanceOf(UserNotFoundException.class);

        verifyNoInteractions(auditService);
    }

    @Test
    void deactivateChecksTheManageUsersPermission() {
        UserAccount actor = adminActor();
        UserAccount target = new UserAccount("target@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        prepareMutation(actor, 2L, target);
        doThrow(new PermissionDeniedException(Permission.MANAGE_USERS))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_USERS);

        assertThatThrownBy(() -> userService.deactivate(actor, 2L))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void deactivateOnAnAlreadyDeactivatedAccountIsIdempotentAndWritesNoDuplicateAuditRecordOrTokenVersionBump() {
        UserAccount actor = adminActor();
        UserAccount existing = new UserAccount("already-deactivated@example.com", UserRole.MUSICIAN, UserStatus.DEACTIVATED, NOW);
        long versionBefore = existing.getTokenVersion();
        prepareMutation(actor, 50L, existing);

        userService.deactivate(actor, 50L);

        assertThat(existing.getTokenVersion()).isEqualTo(versionBefore);
        assertThat(existing.getStatus()).isEqualTo(UserStatus.DEACTIVATED);
        verify(userAccountRepository, never()).saveAndFlush(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void deactivateRejectsAnActorTargetingThemselvesEvenWithEveryPermission() {
        UserAccount actor = adminActor();
        ReflectionTestUtils.setField(actor, "id", 100L);

        assertThatThrownBy(() -> userService.deactivate(actor, 100L))
                .isInstanceOf(SelfTargetNotAllowedException.class);

        verify(userAccountRepository, never()).findById(any());
        verifyNoInteractions(auditService);
    }

    // ---- edit() ----

    @Test
    void editUpdatesFieldsWithoutARoleChangeAndWritesAnAuditRecord() {
        UserAccount actor = adminActor();
        UserAccount existing = new UserAccount("old@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        prepareMutation(actor, 7L, existing);

        UserAccount edited = userService.edit(actor, 7L, updateRequest("new@example.com", UserRole.MUSICIAN));

        assertThat(edited.getEmail()).isEqualTo("new@example.com");
        assertThat(edited.getRole()).isEqualTo(UserRole.MUSICIAN);
        verify(userAccountRepository).saveAndFlush(existing);
        verify(auditService).record(eq(actor.getId()), eq("USER_UPDATED"), eq("UserAccount"), eq(7L), any());
        verify(permissionService, never()).requirePermission(actor, Permission.MANAGE_ADMIN_ROLES);
    }

    @Test
    void editingAnUnchangedEmailIsNotTreatedAsADuplicate() {
        UserAccount actor = adminActor();
        UserAccount existing = new UserAccount("same@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        prepareMutation(actor, 8L, existing);

        assertThatCode(() -> userService.edit(actor, 8L, updateRequest("same@example.com", UserRole.MUSICIAN)))
                .doesNotThrowAnyException();

        verify(userAccountRepository, never()).existsByEmail(anyString());
        verify(userAccountRepository, never()).saveAndFlush(existing);
        verifyNoInteractions(auditService);
    }

    @Test
    void editRejectsChangingToAnEmailAlreadyHeldByAnotherAccount() {
        UserAccount actor = adminActor();
        UserAccount existing = new UserAccount("current@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        prepareMutation(actor, 9L, existing);
        when(userAccountRepository.existsByEmail("someoneelses@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.edit(
                actor, 9L, updateRequest("someoneelses@example.com", UserRole.MUSICIAN)))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void editTranslatesALostUniqueConstraintRaceIntoADuplicateEmailException() {
        UserAccount actor = adminActor();
        UserAccount existing = new UserAccount("race-edit@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        prepareMutation(actor, 31L, existing);
        when(userAccountRepository.existsByEmail("taken-race@example.com")).thenReturn(false);
        when(userAccountRepository.saveAndFlush(existing))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThatThrownBy(() -> userService.edit(
                actor, 31L, updateRequest("taken-race@example.com", UserRole.MUSICIAN)))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void editTranslatesALostOptimisticLockRaceIntoAConcurrentUserModificationException() {
        UserAccount actor = adminActor();
        UserAccount existing = new UserAccount("race-edit2@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        prepareMutation(actor, 32L, existing);
        when(userAccountRepository.saveAndFlush(existing))
                .thenThrow(new ObjectOptimisticLockingFailureException(UserAccount.class, 32L));

        assertThatThrownBy(() -> userService.edit(
                actor, 32L, updateRequest("race-edit2-changed@example.com", UserRole.MUSICIAN)))
                .isInstanceOf(ConcurrentUserModificationException.class);
    }

    @Test
    void editEnforcesTheSameMinorGuardianConsentRuleAsCreate() {
        UserAccount actor = adminActor();
        UserAccount existing = new UserAccount("kid@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        prepareMutation(actor, 10L, existing);

        assertThatThrownBy(() -> userService.edit(actor, 10L,
                new UpdateUserRequest("kid@example.com", UserRole.MUSICIAN, true, null, true, 0L)))
                .isInstanceOf(InvalidUserDataException.class);
    }

    @Test
    void editOnAnUnknownUserThrowsUserNotFoundException() {
        UserAccount actor = adminActor();
        prepareMissingMutation(actor, 404L);

        assertThatThrownBy(() -> userService.edit(actor, 404L, updateRequest("x@example.com", UserRole.MUSICIAN)))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void editRejectsAnActorTargetingThemselvesEvenWithEveryPermission() {
        UserAccount actor = adminActor();
        ReflectionTestUtils.setField(actor, "id", 99L);

        assertThatThrownBy(() -> userService.edit(actor, 99L, updateRequest("self@example.com", UserRole.MUSICIAN)))
                .isInstanceOf(SelfTargetNotAllowedException.class);

        verify(userAccountRepository, never()).findById(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void editChangingRoleWithoutManageAdminRolesPermissionIsDenied() {
        UserAccount actor = adminActor();
        UserAccount existing = new UserAccount("promote@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        prepareMutation(actor, 20L, existing);
        doThrow(new PermissionDeniedException(Permission.MANAGE_ADMIN_ROLES))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_ADMIN_ROLES);

        assertThatThrownBy(() -> userService.edit(actor, 20L, updateRequest("promote@example.com", UserRole.ADMIN)))
                .isInstanceOf(PermissionDeniedException.class);

        verify(userAccountRepository, never()).saveAndFlush(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void editDemotingAnAdminWithoutManageAdminRolesPermissionIsAlsoDenied() {
        UserAccount actor = adminActor();
        UserAccount existing = new UserAccount("demote@example.com", UserRole.ADMIN, UserStatus.ACTIVE, NOW);
        prepareMutation(actor, 22L, existing);
        doThrow(new PermissionDeniedException(Permission.MANAGE_ADMIN_ROLES))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_ADMIN_ROLES);

        assertThatThrownBy(() -> userService.edit(actor, 22L, updateRequest("demote@example.com", UserRole.MUSICIAN)))
                .isInstanceOf(PermissionDeniedException.class);

        verify(userAccountRepository, never()).saveAndFlush(any());
    }

    @Test
    void editChangingRoleWithBothPermissionsSucceedsAndWritesAnAuditRecord() {
        UserAccount actor = adminActor();
        UserAccount existing = new UserAccount("promote-ok@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        prepareMutation(actor, 21L, existing);

        UserAccount edited = userService.edit(actor, 21L, updateRequest("promote-ok@example.com", UserRole.ADMIN));

        assertThat(edited.getRole()).isEqualTo(UserRole.ADMIN);
        verify(permissionService).requirePermission(actor, Permission.MANAGE_USERS);
        verify(permissionService).requirePermission(actor, Permission.MANAGE_ADMIN_ROLES);
        verify(auditService).record(eq(actor.getId()), eq("USER_UPDATED"), eq("UserAccount"), eq(21L), any());
    }

    @Test
    void editWithoutARoleChangeDoesNotRequireManageAdminRolesPermission() {
        UserAccount actor = adminActor();
        UserAccount existing = new UserAccount("same-role@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        prepareMutation(actor, 23L, existing);

        userService.edit(actor, 23L, updateRequest("same-role@example.com", UserRole.MUSICIAN));

        verify(permissionService, never()).requirePermission(actor, Permission.MANAGE_ADMIN_ROLES);
    }

    @Test
    void editOfAnAdminEmailRequiresManageAdminRolesEvenWithoutARoleChange() {
        UserAccount actor = adminActor();
        UserAccount existing = new UserAccount("target-admin@example.com", UserRole.ADMIN, UserStatus.ACTIVE, NOW);
        prepareMutation(actor, 24L, existing);
        doThrow(new PermissionDeniedException(Permission.MANAGE_ADMIN_ROLES))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_ADMIN_ROLES);

        assertThatThrownBy(() -> userService.edit(
                actor, 24L, updateRequest("attacker@example.com", UserRole.ADMIN)))
                .isInstanceOf(PermissionDeniedException.class);

        assertThat(existing.getEmail()).isEqualTo("target-admin@example.com");
        verify(userAccountRepository, never()).saveAndFlush(existing);
    }

    @Test
    void editRejectsASequentiallyStaleVersionBeforeApplyingChanges() {
        UserAccount actor = adminActor();
        UserAccount existing = new UserAccount("current-version@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        prepareMutation(actor, 25L, existing);
        ReflectionTestUtils.setField(existing, "version", 3L);
        UpdateUserRequest stale = new UpdateUserRequest(
                "stale-write@example.com", UserRole.MUSICIAN, false, null, false, 2L);

        assertThatThrownBy(() -> userService.edit(actor, 25L, stale))
                .isInstanceOf(ConcurrentUserModificationException.class);

        assertThat(existing.getEmail()).isEqualTo("current-version@example.com");
        verify(userAccountRepository, never()).saveAndFlush(existing);
        verifyNoInteractions(auditService);
    }

    @Test
    void demotingAnAdminRevokesAllPermissionGrantsInTheSameMutation() {
        UserAccount actor = adminActor();
        UserAccount existing = new UserAccount("demote-cleanly@example.com", UserRole.ADMIN, UserStatus.ACTIVE, NOW);
        prepareMutation(actor, 26L, existing);

        userService.edit(actor, 26L, updateRequest("demote-cleanly@example.com", UserRole.MUSICIAN));

        verify(adminPermissionRepository).deleteByAdmin(existing);
        assertThat(existing.getRole()).isEqualTo(UserRole.MUSICIAN);
    }

    @Test
    void promotingAMusicianWithDependenciesReturnsADetailedConflictWithoutChangingTheRole() {
        UserAccount actor = adminActor();
        UserAccount existing = new UserAccount("dependent@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        prepareMutation(actor, 27L, existing);
        when(musicianGroupRepository.countByMusician(existing)).thenReturn(2L);
        when(eventMusicianAccessRepository.countByMusician(existing)).thenReturn(1L);
        when(sheetMusicianAccessRepository.countByMusician(existing)).thenReturn(3L);

        assertThatThrownBy(() -> userService.edit(
                actor, 27L, updateRequest("dependent@example.com", UserRole.ADMIN)))
                .isInstanceOf(UserRoleTransitionConflictException.class)
                .hasMessageContaining("groupMemberships=2")
                .hasMessageContaining("eventGrants=1")
                .hasMessageContaining("sheetMusicGrants=3");

        assertThat(existing.getRole()).isEqualTo(UserRole.MUSICIAN);
        verify(userAccountRepository, never()).saveAndFlush(existing);
    }

    // ---- get()/list() ----

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
