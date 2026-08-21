package com.banda.publicsite.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateNewsPostRequest(
        @NotBlank @Size(max = 255) String title,
        @NotBlank @Size(max = 10000) String body,
        @NotNull @PositiveOrZero Long version
) {
}
