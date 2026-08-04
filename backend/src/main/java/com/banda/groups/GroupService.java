package com.banda.groups;

import com.banda.audit.AuditService;
import com.banda.groups.dto.CreateGroupRequest;
import com.banda.groups.dto.UpdateGroupRequest;
import com.banda.security.Permission;
import com.banda.security.PermissionService;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Section 4 (Groups) use cases: admin-driven CRUD over {@link Group} plus musician↔group
 * assignment, gated by {@link Permission#MANAGE_GROUPS} independent of the base ADMIN role
 * (Sec.2/Sec.10), and audited on every mutation (Sec.11) — the exact "gate -> mutate ->
 * audit" shape {@code UserService} (PR5) established, copied here rather than reinvented.
 *
 * <p>{@code actor} is trusted as-is: callers (currently {@link GroupController}) MUST
 * resolve it from the authenticated principal, never from client-supplied request data —
 * the same contract {@link PermissionService} and {@link AuditService} themselves document.
 *
 * <p><b>"Delete in-use group" guard (Section 4, design decision #7):</b> {@link #delete}
 * rejects (409 {@link GroupInUseException}) any group that still has {@code musician_group}
 * member rows, checked up front via {@code existsByGroup}. This is deliberately the
 * "block until members are removed" interpretation, not cascade-delete or an
 * auto-reassignment flag: cascading would silently orphan/destroy access data, which the
 * spec explicitly forbids ("no silent orphaned scope"). The admin must
 * {@link #unassignMusician} every member first, then retry the delete. The
 * {@code musician_group.group_id} foreign key (no cascade) is the DB-level backstop for the
 * TOCTOU race where a concurrent {@link #assignMusician} lands between the up-front check
 * and the actual delete — see {@link #delete}'s own catch block.
 *
 * <p><b>Assignment idempotency (Section 4 "Assign" scenario):</b> {@link #assignMusician}/
 * {@link #unassignMusician} are idempotent toggles over the {@code musician_group} join,
 * following the exact pattern {@code PermissionService#grant}/{@code #revoke} established
 * for {@code admin_permission} and explicitly named this PR as expected to copy. Unlike
 * {@code PermissionService}, this class also writes an audit record — but only when the
 * call actually mutated something, mirroring {@code UserService#deactivate}'s "no duplicate
 * audit entry for a no-op" rule.
 */
@Service
@Transactional
public class GroupService {

    private static final Logger log = LoggerFactory.getLogger(GroupService.class);

    private final GroupRepository groupRepository;
    private final MusicianGroupRepository musicianGroupRepository;
    private final UserAccountRepository userAccountRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final Clock clock;
    private final TransactionTemplate requiresNewTransaction;

    public GroupService(GroupRepository groupRepository,
                         MusicianGroupRepository musicianGroupRepository,
                         UserAccountRepository userAccountRepository,
                         PermissionService permissionService,
                         AuditService auditService,
                         Clock clock,
                         PlatformTransactionManager transactionManager) {
        this.groupRepository = groupRepository;
        this.musicianGroupRepository = musicianGroupRepository;
        this.userAccountRepository = userAccountRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.clock = clock;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Group create(UserAccount actor, CreateGroupRequest request) {
        permissionService.requirePermission(actor, Permission.MANAGE_GROUPS);

        Instant now = clock.instant();
        Group group = new Group(request.name(), request.description(), now);
        Group saved = groupRepository.saveAndFlush(group);

        auditService.record(actor.getId(), "GROUP_CREATED", "Group", saved.getId(), "name=" + request.name());
        log.info("Group created: {}", saved.getId());

        return saved;
    }

    public Group edit(UserAccount actor, Long groupId, UpdateGroupRequest request) {
        permissionService.requirePermission(actor, Permission.MANAGE_GROUPS);
        Group group = requireGroup(groupId);

        group.setName(request.name());
        group.setDescription(request.description());
        group.touch(clock.instant());

        try {
            // saveAndFlush (not save): forces the @Version check to happen NOW, inside this
            // method, mirroring UserService#edit's established optimistic-locking pattern.
            groupRepository.saveAndFlush(group);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ConcurrentGroupModificationException();
        }

        auditService.record(actor.getId(), "GROUP_UPDATED", "Group", groupId, "name=" + request.name());
        log.info("Group updated: {}", groupId);

        return group;
    }

    /**
     * Section 4 "Delete in-use group" scenario. See the class-level Javadoc for the full
     * rationale. The up-front {@code existsByGroup} check is the primary, user-friendly
     * guard; the {@link DataIntegrityViolationException} catch around the actual delete is
     * the TOCTOU backstop for a concurrent {@link #assignMusician} that lands between that
     * check and this method's own delete — the {@code musician_group.group_id} FK (no
     * cascade) is what actually rejects the delete in that race, not application code.
     *
     * <p><b>Note on the {@code REQUIRES_NEW} asymmetry with {@link #assignMusician}/
     * {@link #unassignMusician}:</b> unlike those two, this method deliberately does NOT run
     * its mutation in its own {@code REQUIRES_NEW} transaction, because {@code delete} is
     * always the top-level transaction boundary today (called directly from
     * {@link GroupController}, with no caller transaction to protect from being poisoned by a
     * failed nested unit of work). If a future refactor ever calls {@code delete} from
     * *within* another service's transaction, that assumption breaks and this method would
     * need the same {@code REQUIRES_NEW} isolation {@link #assignMusician}/
     * {@link #unassignMusician} already use, to avoid silently reintroducing the poisoning
     * risk those two guard against.
     */
    public void delete(UserAccount actor, Long groupId) {
        permissionService.requirePermission(actor, Permission.MANAGE_GROUPS);
        Group group = requireGroup(groupId);

        if (musicianGroupRepository.existsByGroup(group)) {
            throw new GroupInUseException();
        }

        try {
            groupRepository.delete(group);
            groupRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new GroupInUseException();
        }

        auditService.record(actor.getId(), "GROUP_DELETED", "Group", groupId);
        log.info("Group deleted: {}", groupId);
    }

    @Transactional(readOnly = true)
    public Group get(UserAccount actor, Long groupId) {
        permissionService.requirePermission(actor, Permission.MANAGE_GROUPS);
        return requireGroup(groupId);
    }

    @Transactional(readOnly = true)
    public List<Group> list(UserAccount actor) {
        permissionService.requirePermission(actor, Permission.MANAGE_GROUPS);
        return groupRepository.findAll();
    }

    /**
     * Idempotent: assigning an already-assigned musician is a silent no-op — no duplicate
     * {@code musician_group} row, no duplicate audit entry, mirroring
     * {@code UserService#deactivate}'s idempotency rule. The actual check-then-insert runs
     * in its own {@code REQUIRES_NEW} transaction (mirroring
     * {@code PermissionService#grant}'s identical isolation approach) so that a lost
     * unique-constraint race — which this method catches and swallows as the expected,
     * benign no-op it is — cannot poison the surrounding transaction this call is nested in.
     */
    public void assignMusician(UserAccount actor, Long groupId, Long musicianId) {
        permissionService.requirePermission(actor, Permission.MANAGE_GROUPS);
        Group group = requireGroup(groupId);
        UserAccount musician = requireMusician(musicianId);

        boolean assigned = applyAssignmentWithRaceHandling(musician, group);
        if (assigned) {
            auditService.record(actor.getId(), "MUSICIAN_ASSIGNED_TO_GROUP", "Group", groupId,
                    "musicianId=" + musicianId);
            log.info("Musician {} assigned to group {}", musicianId, groupId);
        }
    }

    /**
     * Idempotent: unassigning a musician who is not currently a member is a silent no-op —
     * no audit entry, mirroring {@link #assignMusician}'s own idempotency and
     * {@code PermissionService#revoke}'s established "no role validation, pure idempotent
     * delete-by-match" pattern.
     */
    public void unassignMusician(UserAccount actor, Long groupId, Long musicianId) {
        permissionService.requirePermission(actor, Permission.MANAGE_GROUPS);
        Group group = requireGroup(groupId);
        UserAccount musician = requireMusician(musicianId);

        long deleted = musicianGroupRepository.deleteByMusicianAndGroup(musician, group);
        if (deleted > 0) {
            auditService.record(actor.getId(), "MUSICIAN_REMOVED_FROM_GROUP", "Group", groupId,
                    "musicianId=" + musicianId);
            log.info("Musician {} removed from group {}", musicianId, groupId);
        }
    }

    @Transactional(readOnly = true)
    public List<UserAccount> listMembers(UserAccount actor, Long groupId) {
        permissionService.requirePermission(actor, Permission.MANAGE_GROUPS);
        Group group = requireGroup(groupId);
        return musicianGroupRepository.findByGroup(group).stream().map(MusicianGroup::getMusician).toList();
    }

    private Boolean applyAssignmentWithRaceHandling(UserAccount musician, Group group) {
        try {
            return requiresNewTransaction.execute(status -> {
                if (musicianGroupRepository.existsByMusicianAndGroup(musician, group)) {
                    return false;
                }
                musicianGroupRepository.saveAndFlush(new MusicianGroup(musician, group));
                return true;
            });
        } catch (DataIntegrityViolationException e) {
            // Lost a concurrent assign() race for the same (musician, group) pair — the
            // other caller's insert already committed. This is the expected, benign no-op
            // this method's own Javadoc promises, not a fault: no ERROR log.
            log.debug("Lost a concurrent assign race for musician {} group {}; already assigned",
                    musician.getId(), group.getId());
            return false;
        }
    }

    private Group requireGroup(Long groupId) {
        return groupRepository.findById(groupId).orElseThrow(() -> new GroupNotFoundException(groupId));
    }

    /**
     * Resolves {@code musicianId} to a {@link UserAccount} AND validates its role is actually
     * {@link UserRole#MUSICIAN} — without this check, an ADMIN account's id could be inserted
     * into {@code musician_group} and would surface via {@link #listMembers}, despite this
     * method's name and {@link MusicianNotFoundException} implying that validation already
     * happened. A non-musician id is treated identically to a nonexistent one (404-style,
     * {@link MusicianNotFoundException}): from {@code MANAGE_GROUPS}' perspective, a non-musician
     * account isn't a valid group-member target either way.
     */
    private UserAccount requireMusician(Long musicianId) {
        UserAccount account = userAccountRepository.findById(musicianId)
                .orElseThrow(() -> new MusicianNotFoundException(musicianId));
        if (account.getRole() != UserRole.MUSICIAN) {
            throw new MusicianNotFoundException(musicianId);
        }
        return account;
    }
}
