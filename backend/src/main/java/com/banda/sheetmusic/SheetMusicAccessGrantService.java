package com.banda.sheetmusic;

import com.banda.groups.Group;
import com.banda.groups.GroupRepository;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserRole;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Write-side counterpart to {@link SheetMusicAccessService} (which owns the read-side
 * {@code canAccess} union logic): validates and persists the group/individual access-scope
 * grants a Section 5 upload requests. Extracted out of {@link SheetMusicService} specifically
 * to bring that class's own constructor back toward the ~7-parameter baseline
 * {@code GroupService}/{@code UserService} established — before this extraction it held 11
 * collaborators, 4 of which (this class's own 4) existed solely to serve
 * {@code applyAccessScope}/{@code requireGroup}/{@code requireMusician}. PR8 (events) is
 * expected to copy this exact split (a read-side "can this actor access X" service plus a
 * write-side "apply this scope to X" service) for its own {@code event_group_access}/
 * {@code event_musician_access} rather than growing one service with both concerns.
 */
@Service
public class SheetMusicAccessGrantService {

    private final SheetGroupAccessRepository sheetGroupAccessRepository;
    private final SheetMusicianAccessRepository sheetMusicianAccessRepository;
    private final GroupRepository groupRepository;
    private final UserAccountRepository userAccountRepository;

    public SheetMusicAccessGrantService(SheetGroupAccessRepository sheetGroupAccessRepository,
                                         SheetMusicianAccessRepository sheetMusicianAccessRepository,
                                         GroupRepository groupRepository,
                                         UserAccountRepository userAccountRepository) {
        this.sheetGroupAccessRepository = sheetGroupAccessRepository;
        this.sheetMusicianAccessRepository = sheetMusicianAccessRepository;
        this.groupRepository = groupRepository;
        this.userAccountRepository = userAccountRepository;
    }

    /**
     * Applies every group id in {@code groupIds} and every musician id in {@code musicianIds}
     * as an access grant on {@code sheetMusic}. Either or both may be {@code null}/empty when
     * {@code allScope} alone is enough, or when the piece isn't scoped to anyone yet (see
     * {@code UploadSheetMusicRequest}'s own Javadoc). An unknown group/musician id throws
     * {@link GroupNotFoundException}/{@link MusicianNotFoundException} — the caller
     * ({@link SheetMusicService#upload}) is responsible for cleaning up any side effect (e.g.
     * an already-stored file) that must not survive this failing partway through.
     */
    public void applyAccessScope(SheetMusic sheetMusic, List<Long> groupIds, List<Long> musicianIds) {
        if (groupIds != null) {
            for (Long groupId : groupIds) {
                Group group = requireGroup(groupId);
                sheetGroupAccessRepository.saveAndFlush(new SheetGroupAccess(sheetMusic, group));
            }
        }
        if (musicianIds != null) {
            for (Long musicianId : musicianIds) {
                UserAccount musician = requireMusician(musicianId);
                sheetMusicianAccessRepository.saveAndFlush(new SheetMusicianAccess(sheetMusic, musician));
            }
        }
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
