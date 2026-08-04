package com.banda.events;

/**
 * Thrown for BOTH "no {@link Event} exists for this id" AND "it exists but the actor cannot
 * access it" (Section 7 "Scoped access" scenario: "Event not visible" — mirrors the exact
 * IDOR-safe 404-not-403 contract {@code SheetMusicNotFoundException} established for Section
 * 5). {@code EventService#get} MUST throw this exact same exception (same message shape, same
 * status) for both cases — never a distinct "forbidden"/"access denied" variant — otherwise
 * the response itself would leak whether the id exists to an unauthorized caller.
 */
public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException(Long eventId) {
        super("Event not found: " + eventId);
    }
}
