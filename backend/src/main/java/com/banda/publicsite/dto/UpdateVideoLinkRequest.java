package com.banda.publicsite.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateVideoLinkRequest(
        @NotBlank @Size(max = 255) String title,
        @NotBlank @Size(max = 255) String url,
        @NotNull @PositiveOrZero Long version
) {
}
