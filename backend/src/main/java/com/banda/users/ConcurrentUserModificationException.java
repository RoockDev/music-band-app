package com.banda.users;

/**
 * {@link UserService#edit} lost an optimistic-lock race: another request modified the same
 * {@link UserAccount} between this request's read and its write. Surfaced directly to the
 * caller as a 409 so they can decide whether to retry with fresh data — unlike
 * {@code UserService#deactivate} (which retries once and swallows the race, per
 * {@code AuthService}'s established retry pattern, since silently failing to deactivate is
 * worse than silently failing a profile edit), an edit's fields (email/role/minor/guardian/
 * consent) are not safely re-appliable without knowing what changed underneath.
 */
public class ConcurrentUserModificationException extends RuntimeException {

    public ConcurrentUserModificationException() {
        super("This user account was modified concurrently by someone else; please retry");
    }
}
