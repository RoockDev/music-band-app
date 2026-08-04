package com.banda.events;

import com.banda.groups.Group;
import com.banda.groups.MusicianGroup;
import com.banda.groups.MusicianGroupRepository;
import com.banda.users.UserAccount;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Section 7 (Calendar/Events) internal-calendar union (OR) access-scoping logic — copies
 * {@code SheetMusicAccessService}'s exact shape (PR7), per this PR's own task note:
 * {@code actor} can access {@code event} if EITHER {@link Event#isAllScope()} is true, OR the
 * actor is individually scoped via {@code event_musician_access}, OR any group the actor
 * belongs to is scoped via {@code event_group_access}. Any one matching path is sufficient —
 * a pure OR, matching Sec.2's own "Union access" scenario.
 *
 * <p>Deliberately never consults {@link Event#isPublic()} — that flag gates the separate,
 * unauthenticated public-site listing (Sec.8, Phase 8), not this internal-calendar check.
 *
 * <p>Checks run cheapest-first (the {@code allScope} flag on the already-loaded entity, then
 * the individual-grant lookup, then the group lookup) so the common "no access" case for an
 * event with few/no grants short-circuits before the group query, mirroring
 * {@code SheetMusicAccessService}'s own rationale.
 */
@Service
public class EventAccessService {

    private final EventGroupAccessRepository eventGroupAccessRepository;
    private final EventMusicianAccessRepository eventMusicianAccessRepository;
    private final MusicianGroupRepository musicianGroupRepository;

    public EventAccessService(EventGroupAccessRepository eventGroupAccessRepository,
                               EventMusicianAccessRepository eventMusicianAccessRepository,
                               MusicianGroupRepository musicianGroupRepository) {
        this.eventGroupAccessRepository = eventGroupAccessRepository;
        this.eventMusicianAccessRepository = eventMusicianAccessRepository;
        this.musicianGroupRepository = musicianGroupRepository;
    }

    public boolean canAccess(UserAccount actor, Event event) {
        if (event.isAllScope()) {
            return true;
        }

        if (eventMusicianAccessRepository.existsByEventAndMusician(event, actor)) {
            return true;
        }

        List<Group> actorGroups = musicianGroupRepository.findByMusician(actor).stream()
                .map(MusicianGroup::getGroup)
                .toList();
        if (actorGroups.isEmpty()) {
            return false;
        }

        return eventGroupAccessRepository.existsByEventAndGroupIn(event, actorGroups);
    }
}
