package com.banda.publicsite.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Section 8 (Public Site Content) minimal create-only request for an {@code Album}. */
public record CreateAlbumRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 255) String description
) {
}
