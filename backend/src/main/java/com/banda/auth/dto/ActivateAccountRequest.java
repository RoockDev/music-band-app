package com.banda.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record ActivateAccountRequest(
        @NotBlank String token,
        @NotBlank String newPassword
) {
}
