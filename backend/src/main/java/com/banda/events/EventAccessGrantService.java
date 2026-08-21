package com.banda.events;

import com.banda.groups.Group;
import com.banda.groups.GroupRepository;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserRole;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        synchronizeAccessScope(event, resolveAccessScope(groupIds, musicianIds));
    }

    /** Resolves and validates every target before any access row is changed. */
    public ResolvedEventScope resolveAccessScope(List<Long> groupIds, List<Long> musicianIds) {
        Map<Long, Group> groups = new LinkedHashMap<>();
        for (Long groupId : safeDistinct(groupIds)) {
            groups.put(groupId, requireGroup(groupId));
        }

        Map<Long, UserAccount> musicians = new LinkedHashMap<>();
        for (Long musicianId : safeDistinct(musicianIds)) {
            musicians.put(musicianId, requireMusician(musicianId));
        }
        return new ResolvedEventScope(groups, musicians);
    }

    /** Replaces grants as sets: retained rows stay, obsolete rows go, missing rows are added. */
    public void synchronizeAccessScope(Event event, ResolvedEventScope requested) {
        List<EventGroupAccess> currentGroups = eventGroupAccessRepository.findByEvent(event);
        List<EventMusicianAccess> currentMusicians = eventMusicianAccessRepository.findByEvent(event);
        if (currentGroups.isEmpty() && currentMusicians.isEmpty()
                && requested.groups().isEmpty() && requested.musicians().isEmpty()) {
            return;
        }

        Set<Long> requestedGroupIds = requested.groups().keySet();
        eventGroupAccessRepository.deleteAllInBatch(currentGroups.stream()
                .filter(access -> !requestedGroupIds.contains(access.getGroup().getId()))
                .toList());
        Set<Long> currentGroupIds = currentGroups.stream()
                .map(access -> access.getGroup().getId())
                .collect(java.util.stream.Collectors.toSet());
        requested.groups().forEach((id, group) -> {
            if (!currentGroupIds.contains(id)) {
                eventGroupAccessRepository.save(new EventGroupAccess(event, group));
            }
        });

        Set<Long> requestedMusicianIds = requested.musicians().keySet();
        eventMusicianAccessRepository.deleteAllInBatch(currentMusicians.stream()
                .filter(access -> !requestedMusicianIds.contains(access.getMusician().getId()))
                .toList());
        Set<Long> currentMusicianIds = currentMusicians.stream()
                .map(access -> access.getMusician().getId())
                .collect(java.util.stream.Collectors.toSet());
        requested.musicians().forEach((id, musician) -> {
            if (!currentMusicianIds.contains(id)) {
                eventMusicianAccessRepository.save(new EventMusicianAccess(event, musician));
            }
        });

        eventGroupAccessRepository.flush();
        eventMusicianAccessRepository.flush();
    }

    public EventAccessScope getAccessScope(Event event) {
        List<Long> groupIds = eventGroupAccessRepository.findByEvent(event).stream()
                .map(access -> access.getGroup().getId())
                .distinct()
                .sorted()
                .toList();
        List<Long> musicianIds = eventMusicianAccessRepository.findByEvent(event).stream()
                .map(access -> access.getMusician().getId())
                .distinct()
                .sorted()
                .toList();
        return new EventAccessScope(groupIds, musicianIds);
    }

    private static List<Long> safeDistinct(List<Long> ids) {
        return ids == null ? List.of() : ids.stream().distinct().toList();
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

    public record ResolvedEventScope(Map<Long, Group> groups, Map<Long, UserAccount> musicians) {
        public EventAccessScope toAccessScope() {
            return new EventAccessScope(groups.keySet().stream().sorted().toList(),
                    musicians.keySet().stream().sorted().toList());
        }
    }
}
