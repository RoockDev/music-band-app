package com.banda.sheetmusic;

/**
 * No {@code UserAccount} exists for the musician id an upload's individual-access-scope
 * list targeted — or the resolved account is not actually a {@code MUSICIAN}. Kept local to
 * this feature package (not a reuse of {@code com.banda.users.UserNotFoundException}),
 * following the exact "by feature" rationale {@code com.banda.groups.MusicianNotFoundException}
 * already established for the identical situation.
 */
public class MusicianNotFoundException extends RuntimeException {

    public MusicianNotFoundException(Long musicianId) {
        super("Musician not found: " + musicianId);
    }
}
