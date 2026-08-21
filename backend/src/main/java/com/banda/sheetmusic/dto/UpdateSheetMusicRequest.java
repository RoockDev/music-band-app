package com.banda.sheetmusic.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateSheetMusicRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 255) String composer,
        @NotNull @Positive Long collectionId,
        boolean allScope,
        @Valid List<@NotNull @Positive Long> groupIds,
        @Valid List<@NotNull @Positive Long> musicianIds,
        @NotNull @PositiveOrZero Long version
) {
}
