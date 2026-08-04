package com.banda.sheetmusic;

/**
 * Thrown for BOTH "no {@link SheetMusic} exists for this id" AND "it exists but the actor
 * cannot access it" (Section 5 IDOR-block scenario, design doc's "IDOR-safety" section:
 * "If false -> 404 (not 403) — don't confirm the resource exists to an unauthorized
 * caller"). {@code SheetMusicService#download} MUST throw this exact same exception (same
 * message shape, same status) for both cases — never a distinct "forbidden"/"access denied"
 * variant — otherwise the response itself would leak whether the id exists to an
 * unauthorized caller, defeating the whole point of returning 404 instead of 403.
 */
public class SheetMusicNotFoundException extends RuntimeException {

    public SheetMusicNotFoundException(Long sheetMusicId) {
        super("Sheet music not found: " + sheetMusicId);
    }
}
