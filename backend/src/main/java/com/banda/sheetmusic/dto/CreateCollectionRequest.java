package com.banda.sheetmusic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Section 6 (Sheet Music Collections/Folders): minimal create-only request. Mirrors
 * {@code com.banda.groups.dto.CreateGroupRequest}'s exact shape. */
public record CreateCollectionRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 255) String description
) {
}
