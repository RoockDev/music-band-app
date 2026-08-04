package com.banda.events;

/**
 * {@link EventService#edit}/{@link EventService#cancel} lost an optimistic-lock race: another
 * request modified the same {@link Event} between this request's read and its write.
 * Surfaced directly to the caller as a 409 so they can decide whether to retry with fresh
 * data, mirroring {@code com.banda.groups.ConcurrentGroupModificationException}'s identical
 * rationale.
 */
public class ConcurrentEventModificationException extends RuntimeException {

    public ConcurrentEventModificationException() {
        super("This event was modified concurrently by someone else; please retry");
    }
}
