package com.banda.publicsite.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Section 8 (Public Site Content) minimal create-only request for a {@code NewsPost}. */
public record CreateNewsPostRequest(
        @NotBlank @Size(max = 255) String title,
        @NotBlank String body
) {
}
