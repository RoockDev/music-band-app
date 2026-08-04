package com.banda.events.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Section 7 edit request: core details only. Access scope (group/musician grants) is set once
 * at creation, mirroring {@code SheetMusic}'s own "no re-scoping endpoint in this PR" scope —
 * a follow-up PR can add scope editing if a real need arises.
 */
public record UpdateEventRequest(
        @NotBlank String title,
        String description,
        String location,
        @NotNull Instant startsAt,
        boolean isPublic,
        boolean allScope
) {
}
