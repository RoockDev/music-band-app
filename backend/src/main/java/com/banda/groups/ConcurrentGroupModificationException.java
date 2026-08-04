package com.banda.groups;

/**
 * {@link GroupService#edit} lost an optimistic-lock race: another request modified the same
 * {@link Group} between this request's read and its write. Surfaced directly to the caller
 * as a 409 so they can decide whether to retry with fresh data, mirroring
 * {@code com.banda.users.ConcurrentUserModificationException}'s identical rationale — a
 * group edit's fields (name/description) are not safely re-appliable without knowing what
 * changed underneath.
 */
public class ConcurrentGroupModificationException extends RuntimeException {

    public ConcurrentGroupModificationException() {
        super("This group was modified concurrently by someone else; please retry");
    }
}
