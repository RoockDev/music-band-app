package com.banda.groups;

/**
 * No {@code UserAccount} exists for the musician id an assign/unassign request targeted.
 * Kept local to this feature package (not a reuse of {@code com.banda.users.UserNotFoundException})
 * so it can be mapped to a 404 directly by {@link GroupController}'s own local exception
 * handler, matching the "by feature" package boundary design decision #8 — a group's own
 * assignment endpoint should not couple its error handling to the users feature's controller.
 */
public class MusicianNotFoundException extends RuntimeException {

    public MusicianNotFoundException(Long musicianId) {
        super("Musician not found: " + musicianId);
    }
}
