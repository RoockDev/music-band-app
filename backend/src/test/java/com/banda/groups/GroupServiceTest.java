package com.banda.groups;

import com.banda.audit.AuditService;
import com.banda.groups.dto.CreateGroupRequest;
import com.banda.groups.dto.UpdateGroupRequest;
import com.banda.security.Permission;
import com.banda.security.PermissionDeniedException;
import com.banda.security.PermissionService;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Clock;
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
 * Section 4 (Groups): CRUD gated by {@link Permission#MANAGE_GROUPS} independent of the base
 * ADMIN role (Sec.2/Sec.10) and audited on every mutation (Sec.11), following the exact
 * "gate -> mutate -> audit" shape {@code UserService} established. The core deliverable of
 * this PR is {@link #deleteOnAGroupWithMembersThrowsGroupInUseException}: the "Delete in-use
 * group" scenario — no silent orphaned scope, explicit reassignment required first.
 */
class GroupServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private GroupRepository groupRepository;
    private MusicianGroupRepository musicianGroupRepository;
    private UserAccountRepository userAccountRepository;
    private PermissionService permissionService;
    private AuditService auditService;
    private GroupService groupService;

    @BeforeEach
    void setUp() {
        groupRepository = mock(GroupRepository.class);
        musicianGroupRepository = mock(MusicianGroupRepository.class);
        userAccountRepository = mock(UserAccountRepository.class);
        permissionService = mock(PermissionService.class);
        auditService = mock(AuditService.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        groupService = new GroupService(groupRepository, musicianGroupRepository, userAccountRepository,
                permissionService, auditService, clock, new NoOpTransactionManager());

        when(groupRepository.saveAndFlush(any(Group.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private UserAccount adminActor() {
        return new UserAccount("admin@example.com", UserRole.ADMIN, UserStatus.ACTIVE, NOW);
    }

    private UserAccount musician(Long id) {
        UserAccount musician = new UserAccount("musician" + id + "@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        org.springframework.test.util.ReflectionTestUtils.setField(musician, "id", id);
        return musician;
    }

    // ---- create() ----

    @Test
    void createChecksTheManageGroupsPermissionBeforeDoingAnythingElse() {
        UserAccount actor = adminActor();
        doThrow(new PermissionDeniedException(Permission.MANAGE_GROUPS))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_GROUPS);

        assertThatThrownBy(() -> groupService.create(actor, new CreateGroupRequest("Choir", "Voices")))
                .isInstanceOf(PermissionDeniedException.class);

        verifyNoInteractions(groupRepository);
        verifyNoInteractions(auditService);
    }

    @Test
    void createPersistsAGroupAndWritesAnAuditRecord() {
        UserAccount actor = adminActor();

        Group created = groupService.create(actor, new CreateGroupRequest("Brass Section", "Trumpets and trombones"));

        ArgumentCaptor<Group> savedCaptor = ArgumentCaptor.forClass(Group.class);
        verify(groupRepository).saveAndFlush(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getName()).isEqualTo("Brass Section");
        assertThat(savedCaptor.getValue().getDescription()).isEqualTo("Trumpets and trombones");
        assertThat(created.getName()).isEqualTo("Brass Section");
        verify(auditService).record(eq(actor.getId()), eq("GROUP_CREATED"), eq("Group"), any(), anyString());
    }

    @Test
    void createWithANullDescriptionSucceeds() {
        UserAccount actor = adminActor();

        Group created = groupService.create(actor, new CreateGroupRequest("Choir", null));

        assertThat(created.getDescription()).isNull();
    }

    // ---- edit() ----

    @Test
    void editUpdatesFieldsAndWritesAnAuditRecord() {
        UserAccount actor = adminActor();
        Group existing = new Group("Old Name", "Old description", NOW);
        when(groupRepository.findById(5L)).thenReturn(Optional.of(existing));

        Group edited = groupService.edit(actor, 5L, new UpdateGroupRequest("New Name", "New description"));

        assertThat(edited.getName()).isEqualTo("New Name");
        assertThat(edited.getDescription()).isEqualTo("New description");
        verify(groupRepository).saveAndFlush(existing);
        verify(auditService).record(eq(actor.getId()), eq("GROUP_UPDATED"), eq("Group"), eq(5L), anyString());
    }

    @Test
    void editOnAnUnknownGroupThrowsGroupNotFoundException() {
        UserAccount actor = adminActor();
        when(groupRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> groupService.edit(actor, 404L, new UpdateGroupRequest("X", null)))
                .isInstanceOf(GroupNotFoundException.class);

        verifyNoInteractions(auditService);
    }

    @Test
    void editChecksTheManageGroupsPermission() {
        UserAccount actor = adminActor();
        doThrow(new PermissionDeniedException(Permission.MANAGE_GROUPS))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_GROUPS);

        assertThatThrownBy(() -> groupService.edit(actor, 1L, new UpdateGroupRequest("X", null)))
                .isInstanceOf(PermissionDeniedException.class);

        verifyNoInteractions(groupRepository);
    }

    @Test
    void editTranslatesALostOptimisticLockRaceIntoAConcurrentGroupModificationException() {
        UserAccount actor = adminActor();
        Group existing = new Group("Race Group", null, NOW);
        when(groupRepository.findById(6L)).thenReturn(Optional.of(existing));
        when(groupRepository.saveAndFlush(existing))
                .thenThrow(new ObjectOptimisticLockingFailureException(Group.class, 6L));

        assertThatThrownBy(() -> groupService.edit(actor, 6L, new UpdateGroupRequest("Race Group 2", null)))
                .isInstanceOf(ConcurrentGroupModificationException.class);
    }

    // ---- delete() ----

    @Test
    void deleteRemovesAnEmptyGroupAndWritesAnAuditRecord() {
        UserAccount actor = adminActor();
        Group existing = new Group("Empty Group", null, NOW);
        when(groupRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(musicianGroupRepository.existsByGroup(existing)).thenReturn(false);

        groupService.delete(actor, 7L);

        verify(groupRepository).delete(existing);
        verify(auditService).record(eq(actor.getId()), eq("GROUP_DELETED"), eq("Group"), eq(7L));
    }

    @Test
    void deleteOnAGroupWithMembersThrowsGroupInUseException() {
        UserAccount actor = adminActor();
        Group existing = new Group("Occupied Group", null, NOW);
        when(groupRepository.findById(8L)).thenReturn(Optional.of(existing));
        when(musicianGroupRepository.existsByGroup(existing)).thenReturn(true);

        assertThatThrownBy(() -> groupService.delete(actor, 8L))
                .isInstanceOf(GroupInUseException.class);

        verify(groupRepository, never()).delete(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void deleteOnAnUnknownGroupThrowsGroupNotFoundException() {
        UserAccount actor = adminActor();
        when(groupRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> groupService.delete(actor, 999L))
                .isInstanceOf(GroupNotFoundException.class);

        verifyNoInteractions(auditService);
    }

    @Test
    void deleteChecksTheManageGroupsPermission() {
        UserAccount actor = adminActor();
        doThrow(new PermissionDeniedException(Permission.MANAGE_GROUPS))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_GROUPS);

        assertThatThrownBy(() -> groupService.delete(actor, 1L))
                .isInstanceOf(PermissionDeniedException.class);

        verifyNoInteractions(groupRepository);
    }

    @Test
    void deleteTranslatesALostConcurrentAssignRaceIntoAGroupInUseException() {
        UserAccount actor = adminActor();
        Group existing = new Group("TOCTOU Group", null, NOW);
        when(groupRepository.findById(9L)).thenReturn(Optional.of(existing));
        // The up-front check passes (no members yet)...
        when(musicianGroupRepository.existsByGroup(existing)).thenReturn(false);
        // ...but a concurrent assignMusician() call committed between that check and the
        // actual delete, so the FK constraint on musician_group.group_id rejects it.
        doThrow(new DataIntegrityViolationException("update or delete on table \"band_group\" violates foreign key constraint"))
                .when(groupRepository).delete(existing);

        assertThatThrownBy(() -> groupService.delete(actor, 9L))
                .isInstanceOf(GroupInUseException.class);

        verifyNoInteractions(auditService);
    }

    // ---- get()/list() ----

    @Test
    void getReturnsTheGroupAfterCheckingThePermission() {
        UserAccount actor = adminActor();
        Group existing = new Group("Viewed Group", null, NOW);
        when(groupRepository.findById(11L)).thenReturn(Optional.of(existing));

        Group result = groupService.get(actor, 11L);

        assertThat(result).isSameAs(existing);
        verify(permissionService).requirePermission(actor, Permission.MANAGE_GROUPS);
    }

    @Test
    void getOnAnUnknownGroupThrowsGroupNotFoundException() {
        UserAccount actor = adminActor();
        when(groupRepository.findById(500L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> groupService.get(actor, 500L)).isInstanceOf(GroupNotFoundException.class);
    }

    @Test
    void listReturnsAllGroupsAfterCheckingThePermission() {
        UserAccount actor = adminActor();
        Group one = new Group("One", null, NOW);
        Group two = new Group("Two", null, NOW);
        when(groupRepository.findAll()).thenReturn(List.of(one, two));

        List<Group> result = groupService.list(actor);

        assertThat(result).containsExactly(one, two);
        verify(permissionService).requirePermission(actor, Permission.MANAGE_GROUPS);
    }

    // ---- assignMusician() ----

    @Test
    void assignChecksTheManageGroupsPermission() {
        UserAccount actor = adminActor();
        doThrow(new PermissionDeniedException(Permission.MANAGE_GROUPS))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_GROUPS);

        assertThatThrownBy(() -> groupService.assignMusician(actor, 1L, 2L))
                .isInstanceOf(PermissionDeniedException.class);

        verifyNoInteractions(groupRepository);
        verifyNoInteractions(musicianGroupRepository);
    }

    @Test
    void assignPersistsTheJoinRowAndWritesAnAuditRecord() {
        UserAccount actor = adminActor();
        Group group = new Group("Choir", null, NOW);
        UserAccount targetMusician = musician(20L);
        when(groupRepository.findById(12L)).thenReturn(Optional.of(group));
        when(userAccountRepository.findById(20L)).thenReturn(Optional.of(targetMusician));
        when(musicianGroupRepository.existsByMusicianAndGroup(targetMusician, group)).thenReturn(false);

        groupService.assignMusician(actor, 12L, 20L);

        ArgumentCaptor<MusicianGroup> captor = ArgumentCaptor.forClass(MusicianGroup.class);
        verify(musicianGroupRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getGroup()).isSameAs(group);
        assertThat(captor.getValue().getMusician()).isSameAs(targetMusician);
        verify(auditService).record(eq(actor.getId()), eq("MUSICIAN_ASSIGNED_TO_GROUP"), eq("Group"), eq(12L), anyString());
    }

    @Test
    void assignOnAnUnknownGroupThrowsGroupNotFoundException() {
        UserAccount actor = adminActor();
        when(groupRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> groupService.assignMusician(actor, 404L, 1L))
                .isInstanceOf(GroupNotFoundException.class);

        verifyNoInteractions(musicianGroupRepository);
    }

    @Test
    void assignOnAnUnknownMusicianThrowsMusicianNotFoundException() {
        UserAccount actor = adminActor();
        Group group = new Group("Choir", null, NOW);
        when(groupRepository.findById(13L)).thenReturn(Optional.of(group));
        when(userAccountRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> groupService.assignMusician(actor, 13L, 999L))
                .isInstanceOf(MusicianNotFoundException.class);

        verifyNoInteractions(musicianGroupRepository);
    }

    @Test
    void assignIsIdempotentWhenAlreadyAssignedAndWritesNoDuplicateAuditRecord() {
        UserAccount actor = adminActor();
        Group group = new Group("Choir", null, NOW);
        UserAccount targetMusician = musician(21L);
        when(groupRepository.findById(14L)).thenReturn(Optional.of(group));
        when(userAccountRepository.findById(21L)).thenReturn(Optional.of(targetMusician));
        when(musicianGroupRepository.existsByMusicianAndGroup(targetMusician, group)).thenReturn(true);

        groupService.assignMusician(actor, 14L, 21L);

        verify(musicianGroupRepository, never()).saveAndFlush(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void assignSwallowsALostUniqueConstraintRaceAndWritesNoAuditRecord() {
        UserAccount actor = adminActor();
        Group group = new Group("Choir", null, NOW);
        UserAccount targetMusician = musician(22L);
        when(groupRepository.findById(15L)).thenReturn(Optional.of(group));
        when(userAccountRepository.findById(22L)).thenReturn(Optional.of(targetMusician));
        when(musicianGroupRepository.existsByMusicianAndGroup(targetMusician, group)).thenReturn(false);
        when(musicianGroupRepository.saveAndFlush(any(MusicianGroup.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThatCode(() -> groupService.assignMusician(actor, 15L, 22L)).doesNotThrowAnyException();

        verifyNoInteractions(auditService);
    }

    // ---- unassignMusician() ----

    @Test
    void unassignChecksTheManageGroupsPermission() {
        UserAccount actor = adminActor();
        doThrow(new PermissionDeniedException(Permission.MANAGE_GROUPS))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_GROUPS);

        assertThatThrownBy(() -> groupService.unassignMusician(actor, 1L, 2L))
                .isInstanceOf(PermissionDeniedException.class);

        verifyNoInteractions(musicianGroupRepository);
    }

    @Test
    void unassignRemovesTheJoinRowAndWritesAnAuditRecord() {
        UserAccount actor = adminActor();
        Group group = new Group("Choir", null, NOW);
        UserAccount targetMusician = musician(23L);
        when(groupRepository.findById(16L)).thenReturn(Optional.of(group));
        when(userAccountRepository.findById(23L)).thenReturn(Optional.of(targetMusician));
        when(musicianGroupRepository.deleteByMusicianAndGroup(targetMusician, group)).thenReturn(1L);

        groupService.unassignMusician(actor, 16L, 23L);

        verify(auditService).record(eq(actor.getId()), eq("MUSICIAN_REMOVED_FROM_GROUP"), eq("Group"), eq(16L), anyString());
    }

    @Test
    void unassignIsIdempotentWhenNotAMemberAndWritesNoAuditRecord() {
        UserAccount actor = adminActor();
        Group group = new Group("Choir", null, NOW);
        UserAccount targetMusician = musician(24L);
        when(groupRepository.findById(17L)).thenReturn(Optional.of(group));
        when(userAccountRepository.findById(24L)).thenReturn(Optional.of(targetMusician));
        when(musicianGroupRepository.deleteByMusicianAndGroup(targetMusician, group)).thenReturn(0L);

        groupService.unassignMusician(actor, 17L, 24L);

        verifyNoInteractions(auditService);
    }

    @Test
    void unassignOnAnUnknownGroupThrowsGroupNotFoundException() {
        UserAccount actor = adminActor();
        when(groupRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> groupService.unassignMusician(actor, 404L, 1L))
                .isInstanceOf(GroupNotFoundException.class);
    }

    @Test
    void unassignOnAnUnknownMusicianThrowsMusicianNotFoundException() {
        UserAccount actor = adminActor();
        Group group = new Group("Choir", null, NOW);
        when(groupRepository.findById(18L)).thenReturn(Optional.of(group));
        when(userAccountRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> groupService.unassignMusician(actor, 18L, 999L))
                .isInstanceOf(MusicianNotFoundException.class);
    }

    // ---- listMembers() ----

    @Test
    void listMembersReturnsMusiciansInTheGroupAfterCheckingThePermission() {
        UserAccount actor = adminActor();
        Group group = new Group("Choir", null, NOW);
        UserAccount member1 = musician(30L);
        UserAccount member2 = musician(31L);
        when(groupRepository.findById(19L)).thenReturn(Optional.of(group));
        when(musicianGroupRepository.findByGroup(group))
                .thenReturn(List.of(new MusicianGroup(member1, group), new MusicianGroup(member2, group)));

        List<UserAccount> members = groupService.listMembers(actor, 19L);

        assertThat(members).containsExactly(member1, member2);
        verify(permissionService).requirePermission(actor, Permission.MANAGE_GROUPS);
    }

    /**
     * Minimal fake transaction manager (no real resource/connection) used purely to drive
     * {@link org.springframework.transaction.support.TransactionTemplate}'s real
     * begin/commit/rollback control flow inside {@link GroupService#assignMusician}, without
     * needing a database — mirrors {@code PermissionServiceTest}'s equivalent fake.
     */
    private static class NoOpTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // no-op: no real resource to begin
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // no-op: no real resource to commit
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // no-op: no real resource to roll back
        }
    }
}
