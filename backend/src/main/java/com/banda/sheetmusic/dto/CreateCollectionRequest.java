package com.banda.sheetmusic.dto;

import jakarta.validation.constraints.NotBlank;

/** Section 6 (Sheet Music Collections/Folders): minimal create-only request. Mirrors
 * {@code com.banda.groups.dto.CreateGroupRequest}'s exact shape. */
public record CreateCollectionRequest(
        @NotBlank String name,
        String description
) {
}
