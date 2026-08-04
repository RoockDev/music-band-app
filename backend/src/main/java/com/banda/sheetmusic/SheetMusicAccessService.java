package com.banda.sheetmusic;

import com.banda.groups.Group;
import com.banda.groups.MusicianGroup;
import com.banda.groups.MusicianGroupRepository;
import com.banda.users.UserAccount;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Section 5/6 union (OR) access-scoping logic (design doc's "IDOR-safety + authenticated
 * PDF serving" section): {@code actor} can access {@code sheetMusic} if EITHER
 * {@link SheetMusic#isAllScope()} is true, OR the actor is individually scoped via
 * {@code sheet_musician_access}, OR any group the actor belongs to is scoped via
 * {@code sheet_group_access}. Any one matching path is sufficient — this is a pure OR, never
 * an AND, matching Sec.2's own "Union access" scenario.
 *
 * <p>Checks run cheapest-first (the {@code allScope} flag on the already-loaded entity,
 * then the individual-grant lookup, then the group lookup) so the common "no access" case
 * for a piece with few/no grants short-circuits before the group query, and a grant found
 * early skips the rest entirely — {@code SheetMusicService#download}/upload-time scope
 * assignment are the current callers; PR8 (events) is expected to copy this exact shape for
 * its own {@code event_group_access}/{@code event_musician_access} scoping rather than
 * reinventing it.
 */
@Service
public class SheetMusicAccessService {

    private final SheetGroupAccessRepository sheetGroupAccessRepository;
    private final SheetMusicianAccessRepository sheetMusicianAccessRepository;
    private final MusicianGroupRepository musicianGroupRepository;

    public SheetMusicAccessService(SheetGroupAccessRepository sheetGroupAccessRepository,
                                    SheetMusicianAccessRepository sheetMusicianAccessRepository,
                                    MusicianGroupRepository musicianGroupRepository) {
        this.sheetGroupAccessRepository = sheetGroupAccessRepository;
        this.sheetMusicianAccessRepository = sheetMusicianAccessRepository;
        this.musicianGroupRepository = musicianGroupRepository;
    }

    public boolean canAccess(UserAccount actor, SheetMusic sheetMusic) {
        if (sheetMusic.isAllScope()) {
            return true;
        }

        if (sheetMusicianAccessRepository.existsBySheetMusicAndMusician(sheetMusic, actor)) {
            return true;
        }

        List<Group> actorGroups = musicianGroupRepository.findByMusician(actor).stream()
                .map(MusicianGroup::getGroup)
                .toList();
        if (actorGroups.isEmpty()) {
            return false;
        }

        return sheetGroupAccessRepository.existsBySheetMusicAndGroupIn(sheetMusic, actorGroups);
    }
}
