package com.banda.publicsite.dto;

import jakarta.validation.constraints.NotBlank;

/** Section 8 (Public Site Content) minimal create-only request for a {@code NewsPost}. */
public record CreateNewsPostRequest(
        @NotBlank String title,
        @NotBlank String body
) {
}
