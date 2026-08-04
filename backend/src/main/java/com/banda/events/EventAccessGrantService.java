package com.banda.events;

import com.banda.groups.Group;
import com.banda.groups.GroupRepository;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserRole;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Write-side counterpart to {@link EventAccessService} (which owns the read-side
 * {@code canAccess} union logic): validates and persists the group/individual internal-
 * calendar access-scope grants a Section 7 event create requests. Extracted from day one —
 * copies {@code SheetMusicAccessGrantService}'s exact split (a read-side "can this actor
 * access X" service plus a write-side "apply this scope to X" service), per that class's own
 * task note for Phase 7 to reuse rather than growing {@link EventService} into an
 * oversized constructor the way {@code SheetMusicService} had to be retrofitted away from.
 */
@Service
public class EventAccessGrantService {

    private final EventGroupAccessRepository eventGroupAccessRepository;
    private final EventMusicianAccessRepository eventMusicianAccessRepository;
    private final GroupRepository groupRepository;
    private final UserAccountRepository userAccountRepository;

    public EventAccessGrantService(EventGroupAccessRepository eventGroupAccessRepository,
                                    EventMusicianAccessRepository eventMusicianAccessRepository,
                                    GroupRepository groupRepository,
                                    UserAccountRepository userAccountRepository) {
        this.eventGroupAccessRepository = eventGroupAccessRepository;
        this.eventMusicianAccessRepository = eventMusicianAccessRepository;
        this.groupRepository = groupRepository;
        this.userAccountRepository = userAccountRepository;
    }

    /**
     * Applies every group id in {@code groupIds} and every musician id in {@code musicianIds}
     * as an internal-calendar access grant on {@code event}. Either or both may be
     * {@code null}/empty when {@code allScope} alone is enough, or when the event isn't
     * scoped to anyone yet. An unknown group/musician id throws
     * {@link GroupNotFoundException}/{@link MusicianNotFoundException}.
     *
     * <p><b>Duplicate ids are deduped, not rejected:</b> a caller sending e.g.
     * {@code groupIds:[5,5]} ends up with a single grant row for group 5, not a
     * {@code event_group_access}/{@code event_musician_access} unique-constraint violation
     * surfacing as an uncaught {@link org.springframework.dao.DataIntegrityViolationException}
     * (which {@code GlobalExceptionHandler} would otherwise map to a misleading 503). This
     * mirrors the codebase's established "redundant caller data is a silent idempotent no-op,
     * not a client error" convention ({@code GroupService#assignMusician}/{@code
     * #unassignMusician}, {@code UserService#deactivate}) rather than introducing a new
     * validation-exception type for what is, functionally, the exact same grant applied twice.
     */
    public void applyAccessScope(Event event, List<Long> groupIds, List<Long> musicianIds) {
        if (groupIds != null) {
            for (Long groupId : distinct(groupIds)) {
                Group group = requireGroup(groupId);
                eventGroupAccessRepository.saveAndFlush(new EventGroupAccess(event, group));
            }
        }
        if (musicianIds != null) {
            for (Long musicianId : distinct(musicianIds)) {
                UserAccount musician = requireMusician(musicianId);
                eventMusicianAccessRepository.saveAndFlush(new EventMusicianAccess(event, musician));
            }
        }
    }

    private static List<Long> distinct(List<Long> ids) {
        return ids.stream().distinct().toList();
    }

    private Group requireGroup(Long groupId) {
        return groupRepository.findById(groupId).orElseThrow(() -> new GroupNotFoundException(groupId));
    }

    /** Mirrors {@code GroupService#requireMusician}: a non-musician id is treated identically
     * to a nonexistent one (404-style), not just a nonexistent id. */
    private UserAccount requireMusician(Long musicianId) {
        UserAccount account = userAccountRepository.findById(musicianId)
                .orElseThrow(() -> new MusicianNotFoundException(musicianId));
        if (account.getRole() != UserRole.MUSICIAN) {
            throw new MusicianNotFoundException(musicianId);
        }
        return account;
    }
}
