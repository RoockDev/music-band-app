package com.banda.events;

/**
 * No {@code UserAccount} exists for the musician id an event's individual-access-scope list
 * targeted — or the resolved account is not actually a {@code MUSICIAN}. Kept local to this
 * feature package, following the exact "by feature" rationale
 * {@code com.banda.sheetmusic.MusicianNotFoundException} already established for the
 * identical situation.
 */
public class MusicianNotFoundException extends RuntimeException {

    public MusicianNotFoundException(Long musicianId) {
        super("Musician not found: " + musicianId);
    }
}
