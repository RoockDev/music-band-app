package com.banda.events.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Complete replacement contract for event metadata and scope. Every scope field and the
 * optimistic-lock version are required so omission can never mean either "keep" or "clear".
 */
public record UpdateEventRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 255) String description,
        @Size(max = 255) String location,
        @NotNull Instant startsAt,
        @NotNull Boolean isPublic,
        @NotNull Boolean allScope,
        @NotNull List<@NotNull @Positive Long> groupIds,
        @NotNull List<@NotNull @Positive Long> musicianIds,
        @NotNull @PositiveOrZero Long version
) {

    @AssertTrue(message = "allScope cannot be combined with group or musician targets")
    public boolean isScopeValid() {
        return !Boolean.TRUE.equals(allScope) || (groupIds != null && groupIds.isEmpty()
                && musicianIds != null && musicianIds.isEmpty());
    }
}
