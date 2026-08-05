package com.banda.publicsite.dto;

import jakarta.validation.constraints.NotBlank;

/** Section 8 (Public Site Content) minimal create-only request for an {@code Album}. */
public record CreateAlbumRequest(
        @NotBlank String name,
        String description
) {
}
