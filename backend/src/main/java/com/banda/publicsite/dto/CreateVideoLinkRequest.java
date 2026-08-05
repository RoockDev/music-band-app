package com.banda.publicsite.dto;

import jakarta.validation.constraints.NotBlank;

/** Section 8 (Public Site Content) minimal create-only request for a {@code VideoLink}. */
public record CreateVideoLinkRequest(
        @NotBlank String title,
        @NotBlank String url
) {
}
