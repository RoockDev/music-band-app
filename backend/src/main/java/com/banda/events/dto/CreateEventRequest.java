package com.banda.events.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * Section 7 (Calendar/Events) create request. {@code isPublic}/{@code allScope} are two
 * independent flags — see {@code Event}'s own Javadoc for why. {@code groupIds}/
 * {@code musicianIds} are the internal-calendar access-scope grants applied atomically with
 * creation, mirroring {@code UploadSheetMusicRequest}'s scoping shape; unlike that DTO, plain
 * {@code boolean} (not boxed {@code Boolean}) is fine here because this is a JSON
 * {@code @RequestBody}, not a multipart {@code @ModelAttribute} bound from an HTML checkbox.
 */
public record CreateEventRequest(
        @NotBlank String title,
        String description,
        String location,
        @NotNull Instant startsAt,
        boolean isPublic,
        boolean allScope,
        List<Long> groupIds,
        List<Long> musicianIds
) {
}
